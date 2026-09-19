/**
 * Transactional customer workflows expose synthetic contacts and durable automatic-pipeline status;
 * variant assessments share the same COBOL policy.
 */
package com.lending.engine.application;

import static com.lending.engine.domain.Model.*;
import static com.lending.engine.domain.CustomerRules.*;
import com.lending.engine.application.Ports.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Transactional customer workflows expose synthetic contacts and durable automatic-pipeline status;
 * variant assessments share the same COBOL policy.
 */
public final class CustomerEngine implements AutoCloseable {
    private final Store store;
    private final Underwriter underwriter;
    private final Clock clock;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2));
    /** Initializes customer engine with the supplied configuration and dependencies. */
    public CustomerEngine(Store store, Underwriter underwriter, Clock clock) {
        this.store=store; this.underwriter=underwriter; this.clock=clock;
        store.write(s -> { for(Job j:s.jobs()) if(Set.of("QUEUED","RUNNING").contains(j.status()))
            s.saveJob(new Job(j.id(),"FAILED",j.count(),j.seed(),j.createdAt(),now(),Map.of(),"Interrupted before completion; rerun explicitly")); return null; });
    }
    /** Validates customer input, evaluates underwriting and atomically saves the initial customer record. */
    public Customer create(CustomerInput input) {
        validate(input);
        Customer c = newCustomer(input,"MANUAL",null,underwriter.evaluate(List.of(input)).get(0));
        return store.write(s -> { s.saveCustomer(c); return c; });
    }
    /** Loads a customer or raises a not-found error. */
    public Customer get(String id) { return store.read(s -> required(s.customer(id),"Customer")); }
    /** Loads the customer synthetic contact details and delivery preferences. */
    public Contact contact(String id){return store.read(s->s.contact(id));}
    /** Loads the latest automatic processing state for a customer. */
    public Pipeline pipeline(String id){return store.read(s->s.pipeline(id));}
    /** Marks the matching automatic pipeline complete after campaign execution succeeds. */
    public void executionCompleted(String id,String runId){store.write(s->{var p=s.pipeline(id);if(p!=null&&Objects.equals(p.runId(),runId)&&!p.state().equals("COMPLETED"))s.savePipeline(new Pipeline(id,p.token(),"COMPLETED",runId,now(),"Step 7 complete: synthetic outbound files generated"),0);return null;});}
    /**
     * Evaluates proposed customer financial inputs with the same underwriting policy used for
     * applications.
     */
    public List<RuleResult> assessVariant(CustomerInput input) { validate(input); return underwriter.evaluate(List.of(input)).get(0); }
    /** Validates pagination and retrieves a page of customer summaries. */
    public Page<CustomerSummary> list(String batch, int offset, int limit) { page(offset,limit); return store.read(s->s.customers(batch,offset,limit)); }
    /**
     * Creates a new profile version and underwriting decisions only when the expected version still
     * matches.
     */
    public Customer update(String id, Update update) {
        require(update != null && update.expectedVersion()>0,"A positive expectedVersion is required"); validate(update.customer());
        Customer original=get(id);
        if(original.current().version()!=update.expectedVersion()) throw new Problem(409,"Customer changed; reload before editing");
        var results=underwriter.evaluate(List.of(update.customer())).get(0);
        return store.write(s -> {
            Customer current=required(s.customer(id),"Customer");
            if(current.current().version()!=update.expectedVersion()) throw new Problem(409,"Customer changed; reload before editing");
            Profile p=new Profile(id,update.expectedVersion()+1,"MANUAL_UPDATE",current.current().batchId(),now(),"SELF_REPORTED",update.customer());
            List<Profile> profiles=new ArrayList<>(current.profiles()); profiles.add(p);
            List<Decision> decisions=new ArrayList<>(current.decisions()); decisions.addAll(decisions(p,results));
            Customer saved=new Customer(id,List.copyOf(profiles),List.copyOf(decisions));
            s.saveCustomer(saved); return saved;
        });
    }
    /**
     * Imports supplied underwriting decisions atomically with source-identity replay and profile-version
     * checks.
     */
    public List<Decision> importDecisions(List<ImportDecision> imports) {
        require(imports!=null && !imports.isEmpty() && imports.size()<=1000,"Import requires 1-1000 decisions");
        for(ImportDecision d:imports) validateImport(d);
        return store.write(s -> {
            List<Decision> result=new ArrayList<>();
            for(ImportDecision d:imports) {
                Decision prior=s.importedDecision(d.sourceSystem(),d.sourceDecisionId());
                if(prior!=null) {
                    if(!sameImport(prior,d)) throw new Problem(409,"Source decision identifier already has different content");
                    result.add(prior); continue;
                }
                Customer c=required(s.customer(d.customerId()),"Customer");
                if(c.current().version()!=d.profileVersion()) throw new Problem(409,"Imported assessment must reference the current profile version");
                if(Instant.parse(d.assessedAt()).isBefore(Instant.parse(c.current().createdAt())))
                    throw new Problem(422,"Assessment predates its customer profile");
                Decision decision=new Decision(UUID.randomUUID().toString(),c.id(),d.profileVersion(),d.product(),d.status(),List.copyOf(d.reasons()),
                    "IMPORTED",d.sourceSystem(),d.sourceDecisionId(),d.policyVersion(),d.assessedAt(),d.validUntil());
                List<Decision> all=new ArrayList<>(c.decisions()); all.add(decision);
                s.indexImport(decision); s.saveCustomer(new Customer(c.id(),c.profiles(),List.copyOf(all))); result.add(decision);
            }
            return result;
        });
    }
    /** Validates the identity, provenance, chronology and expiry of a supplied underwriting decision. */
    private void validateImport(ImportDecision d) {
        require(d!=null,"Null import record"); text(d.customerId(),"customerId",40,true);
        require(d.profileVersion()>0 && d.product()!=null && d.status()!=null,"Profile version, product and status are required");
        text(d.sourceSystem(),"sourceSystem",80,true); text(d.sourceDecisionId(),"sourceDecisionId",120,true);
        require(!d.sourceSystem().equals("LOCAL_SIMULATOR"),"LOCAL_SIMULATOR is a reserved source");
        text(d.policyVersion(),"policyVersion",60,true);
        require(d.reasons()!=null && d.reasons().size()<=20,"reasons must be an array of at most 20 codes");
        for(String reason:d.reasons()) require(reason!=null && reason.matches("[A-Z][A-Z0-9_]{0,59}"),"Invalid reason code");
        require(d.status()==Status.ELIGIBLE || !d.reasons().isEmpty(),"Non-eligible decisions require reasons");
        try {
            Instant at=Instant.parse(d.assessedAt()); LocalDate until=LocalDate.parse(d.validUntil());
            require(!at.isAfter(clock.instant()),"Assessment timestamp is in the future");
            require(!until.isBefore(at.atZone(ZoneOffset.UTC).toLocalDate()),"Expiry precedes assessment date");
        } catch(DateTimeException|NullPointerException e) { throw new Problem(422,"Use an ISO UTC assessment timestamp and YYYY-MM-DD expiry"); }
    }
    /** Checks whether a retried source decision contains the same underwriting content. */
    private static boolean sameImport(Decision a, ImportDecision b) {
        return a.customerId().equals(b.customerId()) && a.profileVersion()==b.profileVersion() && a.product()==b.product()
            && a.status()==b.status() && a.reasons().equals(b.reasons()) && a.policyVersion().equals(b.policyVersion())
            && a.assessedAt().equals(b.assessedAt()) && a.validUntil().equals(b.validUntil());
    }
    /** Queues a bounded synthetic-generation job using the requested population size and seed. */
    public synchronized Job simulate(SimulationRequest request) {
        require(request!=null && request.count()!=null && request.count()>=1 && request.count()<=10000,"Simulation count must be 1-10000");
        require(request.seed()!=null,"Simulation seed is required");
        if(workers.getQueue().remainingCapacity()==0) throw new Problem(429,"Simulation queue is full");
        Job job=new Job(UUID.randomUUID().toString(),"QUEUED",request.count(),request.seed(),now(),null,Map.of(),null);
        store.write(s->{s.saveJob(job);return null;});
        try { workers.execute(()->run(job)); }
        catch(RejectedExecutionException e) { fail(job,"Simulation service is stopping"); throw new Problem(503,"Simulation service is stopping"); }
        return job;
    }
    /** Generates synthetic customers, evaluates underwriting and persists the job outcome. */
    private void run(Job job) {
        try {
            store.write(s->{s.saveJob(new Job(job.id(),"RUNNING",job.count(),job.seed(),job.createdAt(),null,Map.of(),null));return null;});
            List<CustomerInput> inputs=Generator.generate(job.count(),job.seed()); inputs.forEach(com.lending.engine.domain.CustomerRules::validate);
            var evaluated=underwriter.evaluate(inputs);
            List<Customer> customers=new ArrayList<>(); Map<String,Long> counts=new TreeMap<>();
            for(int i=0;i<inputs.size();i++) {
                Customer c=newCustomer(inputs.get(i),"SYNTHETIC",job.id(),evaluated.get(i)); customers.add(c);
                for(Decision d:c.decisions()) counts.merge(d.product()+":"+d.status(),1L,Long::sum);
            }
            store.write(s->{
                customers.forEach(s::saveCustomer);
                s.saveJob(new Job(job.id(),"COMPLETED",job.count(),job.seed(),job.createdAt(),now(),counts,null));return null;
            });
        } catch(Exception e) { fail(job,"Generation failed; no partial population was committed"); System.err.println("Simulation failed: "+e.getClass().getSimpleName()); }
    }
    /** Records a failed synthetic-generation job with its diagnostic message. */
    private void fail(Job j,String error) { store.write(s->{s.saveJob(new Job(j.id(),"FAILED",j.count(),j.seed(),j.createdAt(),now(),Map.of(),error));return null;}); }
    /** Retrieves the requested synthetic-generation job and its progress. */
    public Job job(String id) { return store.read(s->required(s.job(id),"Simulation")); }
    /** Lists recorded generation or training jobs and their outcomes. */
    public List<Job> jobs() { return store.read(Session::jobs); }
    /** Builds and persists a pre-screen population from current eligible underwriting decisions. */
    public Population createPopulation(PopulationRequest request) {
        require(request!=null && request.products()!=null && !request.products().isEmpty(),"Select at least one product");
        require(request.products().stream().noneMatch(Objects::isNull) && new HashSet<>(request.products()).size()==request.products().size(),"Products must be distinct and supported");
        return store.write(s->{
            if(request.batchId()!=null) require("COMPLETED".equals(required(s.job(request.batchId()),"Simulation").status()),"Simulation must complete before screening");
            List<Customer> customers=s.populationCustomers(request.batchId());
            require(!customers.isEmpty(),"No customers in the selected source");
            LocalDate today=LocalDate.now(clock); List<Member> members=new ArrayList<>();
            Set<String> included=new HashSet<>(),policies=new TreeSet<>(); Map<String,Long> exclusions=new TreeMap<>();
            for(Customer c:customers) for(Product product:request.products()) {
                Decision d=c.latest(product); var reasons=exclusionReasons(c.current(),d,today);
                if(d!=null) policies.add(d.policyVersion());
                if(reasons.isEmpty()) { included.add(c.id()); members.add(new Member(c.id(),c.current().version(),product,d.id())); }
                else for(String reason:reasons) exclusions.merge(reason,1L,Long::sum);
            }
            Population p=new Population(UUID.randomUUID().toString(),now(),today.toString(),request.batchId(),List.copyOf(request.products()),
                customers.size(),included.size(),members.size(),exclusions,policies);
            s.savePopulation(p,members); return p;
        });
    }
    /** Retrieves the specified pre-screen or simulation population. */
    public Population population(String id) { return store.read(s->required(s.population(id),"Population")); }
    /** Lists the available population snapshots. */
    public List<Population> populations() { return store.read(Session::populations); }
    /** Returns a page of members and their decision references from a population snapshot. */
    public Page<Member> members(String id,int offset,int limit) { page(offset,limit);return store.read(s->s.members(id,offset,limit)); }
    /** Builds a new customer profile and its initial underwriting decision history. */
    private Customer newCustomer(CustomerInput input,String origin,String batch,List<RuleResult> results) {
        String id=UUID.randomUUID().toString();
        Profile p=new Profile(id,1,origin,batch,now(),origin.equals("SYNTHETIC")?"SYNTHETIC":"SELF_REPORTED",input);
        return new Customer(id,List.of(p),decisions(p,results));
    }
    /** Converts underwriting rule results into decisions linked to the exact customer profile version. */
    private List<Decision> decisions(Profile profile,List<RuleResult> results) {
        return results.stream().map(r->new Decision(UUID.randomUUID().toString(),profile.customerId(),profile.version(),r.product(),r.status(),r.reasons(),
            "SIMULATED","LOCAL_SIMULATOR",null,r.policyVersion(),now(),LocalDate.now(clock).plusDays(30).toString())).toList();
    }
    /** Returns the current application-clock time in serialized form. */
    private String now() { return clock.instant().toString(); }
    /** Returns the required value or raises an application error when it is absent. */
    private static <T> T required(T value,String type) { if(value==null) throw new Problem(404,type+" not found");return value; }
    /** Validates the requested offset and page-size bounds. */
    private static void page(int offset,int limit) { require(offset>=0 && limit>=1 && limit<=200,"Use offset >= 0 and limit 1-200"); }
    /** Stops the synthetic-generation executor and closes the customer store. */
    public void close() {
        workers.shutdown();
        try { if(!workers.awaitTermination(100,TimeUnit.SECONDS)) workers.shutdownNow(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); workers.shutdownNow(); }
    }
}

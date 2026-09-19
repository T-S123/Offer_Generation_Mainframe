/**
 * Atomic qualification and publication preserve catalog snapshots, mandatory gates and offer-scoped COBOL
 * rule versions.
 */
package com.lending.engine.marketing.application;

import com.lending.engine.application.Ports.*;
import com.lending.engine.domain.Model.*;
import com.lending.engine.marketing.domain.Marketing;
import com.lending.engine.marketing.domain.Marketing.*;
import com.lending.engine.marketing.domain.MarketingRules;
import static com.lending.engine.domain.CustomerRules.require;
import static com.lending.engine.marketing.domain.MarketingRules.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Atomic qualification and publication preserve catalog snapshots, mandatory gates and offer-scoped COBOL
 * rule versions.
 */
public final class MarketingEngine {
    private final Store store;
    private final MarketingPorts.Qualifier qualifier;
    private final Clock clock;
    private com.lending.engine.simulation.application.PublishedPolicies published;
    /** Connects the published offer-rule evaluator to the existing qualification workflow. */
    public void published(com.lending.engine.simulation.application.PublishedPolicies published){this.published=published;}
    /** Initializes marketing engine with the supplied configuration and dependencies. */
    public MarketingEngine(Store store,MarketingPorts.Qualifier qualifier,Clock clock){
        this.store=store;this.qualifier=qualifier;this.clock=clock;seed();
    }
    /** Lists the current approved catalog offers. */
    public List<Offer> offers(){return store.read(Session::offers);}
    /** Retrieves the publication receipt associated with a reviewed simulation run. */
    public com.lending.engine.simulation.domain.Simulation.Publication publication(String id){return store.read(s->s.publication(id));}
    /** Atomically publishes a reviewed offer, campaign and rule bundle with its simulation receipt. */
    public com.lending.engine.simulation.domain.Simulation.Publication publish(com.lending.engine.simulation.domain.Simulation.Run run,String fingerprint,String note){
        var d=run.draft();MarketingRules.offer(d.offer());com.lending.engine.simulation.domain.BusinessRules.validate(d.rules());
        return store.write(s->{var prior=s.publication(run.id());if(prior!=null){if(!prior.fingerprint().equals(fingerprint))throw new Problem(409,"Publication request changed");return prior;}
            if(!Objects.equals(s.offer(d.baseline().id()),d.baseline())||!Objects.equals(s.campaign(d.campaign().id()),d.campaign())||!Objects.equals(s.policy(),d.policy()))throw new Problem(409,"Baseline catalog or global policy changed; create and test a fresh draft");
            require(s.offers().size()<1000&&s.campaigns().size()<100,"Catalog limit reached");require(s.campaigns().stream().filter(c->Boolean.TRUE.equals(c.data().active())).count()<20,"At most 20 active campaigns");
            String offerId="BUS-"+d.id()+"-"+d.version(),campaignId="BUS-"+d.id()+"-"+d.version();if(s.offer(offerId)!=null)throw new Problem(409,"This draft version has already been published from another run");
            var c=d.campaign().data();var today=LocalDate.now(clock);require(Boolean.TRUE.equals(d.offer().active())&&inDate(d.offer().startsOn(),d.offer().endsOn(),today)&&Boolean.TRUE.equals(c.active())&&inDate(c.startsOn(),c.endsOn(),today),"Publish requires currently active offer and campaign dates");
            var offer=new Offer(offerId,1,now(),d.offer(),d.rules());var campaign=new Campaign(campaignId,1,now(),new CampaignInput(d.name(),true,c.startsOn(),c.endsOn(),c.priority(),c.capacity(),d.rules().marketing().minimumTenureMonths(),d.rules().marketing().excludeExistingProduct(),List.of(offerId)));
            s.saveOffer(offer);s.saveCampaign(campaign);var receipt=new com.lending.engine.simulation.domain.Simulation.Publication(run.id(),fingerprint,offerId,campaignId,com.lending.engine.simulation.application.PublishedPolicies.version(offer),now(),note);s.savePublication(receipt);return receipt;
        });
    }
    /** Lists the current campaign configurations. */
    public List<Campaign> campaigns(){return store.read(Session::campaigns);}
    /** Returns the active policy configuration used by the workflow. */
    public Policy policy(){return store.read(Session::policy);}
    /** Lists configured customer suppression entries. */
    public List<Suppression> suppressions(){return store.read(Session::suppressions);}
    /** Retrieves the current suppression entry for a customer. */
    public Suppression suppression(String id){return store.read(s->s.suppression(id));}
    /** Loads the requested current catalog offer or reports that it does not exist. */
    public Offer offer(String id){return store.read(s->found(s.offer(id),"Offer"));}
    /** Loads the requested current campaign or reports that it does not exist. */
    public Campaign campaign(String id){return store.read(s->found(s.campaign(id),"Campaign"));}
    /** Persists a new catalog offer revision while retaining its history. */
    public Offer saveOffer(String id,OfferUpdate request){
        identifier(id);require(request!=null,"request is required");MarketingRules.offer(request.offer());
        return store.write(s->{Offer prior=s.offer(id);version(request.expectedVersion(),prior==null?0:prior.version());
            if(prior!=null&&prior.rules()!=null)throw new Problem(409,"Published Business offers are immutable; clone and simulate a new draft");
            require(prior!=null || s.offers().size()<1000,"Local catalog supports 1000 offers");
            if(prior!=null)require(prior.data().product()==request.offer().product(),"An offer's product cannot change; create another offer ID");
            Offer next=new Offer(id,request.expectedVersion()+1,now(),request.offer());s.saveOffer(next);return next;});
    }
    /** Persists a new campaign revision while retaining its history. */
    public Campaign saveCampaign(String id,CampaignUpdate request){
        identifier(id);require(request!=null,"request is required");MarketingRules.campaign(request.campaign());
        return store.write(s->{Campaign prior=s.campaign(id);version(request.expectedVersion(),prior==null?0:prior.version());
            require(prior!=null || s.campaigns().size()<100,"Local catalog supports 100 campaigns");
            request.campaign().offerIds().forEach(o->found(s.offer(o),"Offer "+o));
            Campaign next=new Campaign(id,request.expectedVersion()+1,now(),request.campaign());s.saveCampaign(next);return next;});
    }
    /** Persists a new marketing policy revision while retaining its history. */
    public Policy savePolicy(PolicyUpdate request){
        require(request!=null,"request is required");MarketingRules.policy(request.policy());
        return store.write(s->{version(request.expectedVersion(),s.policy().version());Policy p=new Policy(request.expectedVersion()+1,Marketing.RULE_VERSION,now(),request.policy());s.savePolicy(p);return p;});
    }
    /** Persists a new customer suppression revision while retaining its history. */
    public Suppression saveSuppression(String id,SuppressionUpdate request){
        identifier(id);require(request!=null,"request is required");MarketingRules.suppression(request.suppression());
        return store.write(s->{found(s.customer(id),"Customer");Suppression prior=s.suppression(id);version(request.expectedVersion(),prior==null?0:prior.version());
            Suppression next=new Suppression(id,request.expectedVersion()+1,now(),request.suppression());s.saveSuppression(next);return next;});
    }
    /** Retrieves the selected catalog or suppression revision history. */
    public Object history(String kind,String id){return store.read(s->switch(kind){
        case "offers"->s.offerHistory(id);case "campaigns"->s.campaignHistory(id);case "suppressions"->s.suppressionHistory(id);case "policy"->s.policyHistory();default->throw new Problem(404,"History not found");});}
    /** Evaluates a versioned marketing preview without consuming qualification reservations. */
    public Run preview(RunRequest request){
        require(request!=null,"request is required");identifier(request.requestId());identifier(request.populationId());
        require(request.campaignIds()!=null && !request.campaignIds().isEmpty() && request.campaignIds().size()<=20,"Select 1-20 distinct campaigns");
        request.campaignIds().forEach(MarketingRules::identifier);require(new HashSet<>(request.campaignIds()).size()==request.campaignIds().size(),"Duplicate campaign IDs");
        RunRequest canonical=new RunRequest(request.requestId(),request.populationId(),request.campaignIds().stream().sorted().toList());
        return store.write(s->{
            Run prior=s.requestedRun(canonical.requestId());
            if(prior!=null){if(!prior.request().equals(canonical))throw new Problem(409,"requestId was already used for a different request");return prior;}
            found(s.population(canonical.populationId()),"Population");
            List<Campaign> campaigns=canonical.campaignIds().stream().map(id->found(s.campaign(id),"Campaign"))
                .sorted(Comparator.comparingInt((Campaign c)->c.data().priority()).thenComparing(Campaign::id)).toList();
            List<Offer> offers=campaigns.stream().flatMap(c->c.data().offerIds().stream()).distinct().sorted().map(id->found(s.offer(id),"Offer")).toList();
            String id=UUID.randomUUID().toString();Instant at=clock.instant();
            Evaluation result=evaluate(s,id,canonical.populationId(),campaigns,offers,s.policy(),at);
            Run run=summary(id,canonical,"PREVIEW",at.toString(),null,null,s.policy(),campaigns,offers,result);
            s.saveRun(run);s.saveQualifications(id,result.rows());return run;
        });
    }
    /** Finalizes a fresh preview and commits its qualification reservations atomically. */
    public Run finalizeRun(String id){return store.write(s->finalizeIn(s,id));}
    /** Revalidates preview inputs inside the source transaction before allocating reservations. */
    private Run finalizeIn(Session s,String id){
        Run run=found(s.marketingRun(id),"Run");
        if(run.status().equals("FINALIZED"))return run;
        if(!run.status().equals("PREVIEW"))throw new Problem(409,"Cancelled run cannot be finalized");
        Instant at=clock.instant();
        if(!at.isBefore(Instant.parse(run.createdAt()).plusSeconds(1800)))throw new Problem(409,"Preview expired after 30 minutes; create a new preview");
        if(s.policy().version()!=run.policy().version()
            || run.campaigns().stream().anyMatch(c->s.campaign(c.id()).version()!=c.version())
            || run.offers().stream().anyMatch(o->s.offer(o.id()).version()!=o.version()))throw new Problem(409,"Policy or catalog changed; create a new preview");
        Evaluation fresh=evaluate(s,id,run.request().populationId(),run.campaigns(),run.offers(),run.policy(),at);
        if(!fresh.rows().equals(s.qualifications(id)))throw new Problem(409,"Customer data, suppressions or quota availability changed; create a new preview");
        Map<String,Campaign> campaigns=index(run.campaigns(),Campaign::id);Map<String,Offer> offers=index(run.offers(),Offer::id);
        Map<String,List<Qualification>> groups=fresh.rows().stream().filter(q->q.outcome().equals("QUALIFIED"))
            .collect(Collectors.groupingBy(q->q.customerId()+"/"+q.campaignId(),LinkedHashMap::new,Collectors.toList()));
        for(List<Qualification> rows:groups.values()){
            Qualification q=rows.get(0);Instant expiry=at.plus(Duration.ofDays(run.policy().data().reservationDays()));
            expiry=earlier(expiry,endOfDay(campaigns.get(q.campaignId()).data().endsOn()));
            for(Qualification row:rows){expiry=earlier(expiry,endOfDay(offers.get(row.offerId()).data().endsOn()));
                Decision decision=s.customer(row.customerId()).latest(row.product());expiry=earlier(expiry,endOfDay(decision.validUntil()));}
            Reservation r=new Reservation(UUID.randomUUID().toString(),id,q.riskId(),q.customerId(),q.campaignId(),at.toString(),expiry.toString(),null);s.saveReservation(r);
        }
        Run done=summary(id,run.request(),"FINALIZED",run.createdAt(),at.toString(),null,run.policy(),run.campaigns(),run.offers(),fresh);s.saveRun(done);return done;
    }
    /** Cancels a qualification run and releases its active reservations while preserving history. */
    public Run cancel(String id){return store.write(s->cancelIn(s,id));}
    /** Applies run cancellation within the existing transaction. */
    private Run cancelIn(Session s,String id){
        Run r=found(s.marketingRun(id),"Run");if(r.status().equals("CANCELLED"))return r;String at=now();
        for(Reservation reservation:s.reservations())if(reservation.runId().equals(id) && reservation.releasedAt()==null)
            s.saveReservation(new Reservation(reservation.id(),id,reservation.riskId(),reservation.customerId(),reservation.campaignId(),reservation.createdAt(),reservation.expiresAt(),at));
        Run cancelled=new Run(r.id(),r.request(),"CANCELLED",r.createdAt(),r.finalizedAt(),at,r.policy(),r.campaigns(),r.offers(),r.customerCount(),r.qualifiedCustomers(),r.qualifiedOffers(),r.reservationCount(),r.reasonCounts());
        s.saveRun(cancelled);return cancelled;
    }
    /** Creates or reuses the customer automatic qualification run using current profile and catalog inputs. */
    public Run automatic(String customerId,String token){return store.write(s->{
        var queued=s.pipeline(customerId);if(queued==null||!queued.token().equals(token))throw new Problem(409,"Pipeline source superseded");String requestId="AUTO-"+customerId+"-"+token.substring(0,18);var prior=s.requestedRun(requestId);if(prior!=null)return prior;
        for(var reservation:s.reservations())if(reservation.customerId().equals(customerId)&&reservation.releasedAt()==null){var old=s.marketingRun(reservation.runId());if(old.request().requestId().startsWith("AUTO-"+customerId+"-"))cancelIn(s,old.id());}
        var customer=found(s.customer(customerId),"Customer");var members=new ArrayList<Member>();var reasons=new TreeMap<String,Long>();var policies=new TreeSet<String>();var today=LocalDate.now(clock);
        for(var product:Product.values()){var d=customer.latest(product);var excluded=com.lending.engine.domain.CustomerRules.exclusionReasons(customer.current(),d,today);if(d!=null)policies.add(d.policyVersion());boolean scoped=published!=null&&d!=null&&!date(d.validUntil()).isBefore(today)&&Boolean.TRUE.equals(customer.current().data().marketingOptIn())&&Boolean.FALSE.equals(customer.current().data().prescreenOptOut())&&s.offers().stream().anyMatch(o->o.rules()!=null&&o.data().product()==product&&Boolean.TRUE.equals(o.data().active())&&inDate(o.data().startsOn(),o.data().endsOn(),today)&&published.underwriting(customer,o).eligible());if(excluded.isEmpty()||scoped)members.add(new Member(customerId,customer.current().version(),product,d.id()));else for(var reason:excluded)reasons.merge(reason,1L,Long::sum);}
        String populationId=stable(requestId+":population"),runId=stable(requestId+":run");s.savePopulation(new Population(populationId,now(),today.toString(),customer.current().batchId(),List.of(Product.values()),1,members.isEmpty()?0:1,members.size(),reasons,policies),members);
        var campaigns=s.campaigns().stream().filter(c->Boolean.TRUE.equals(c.data().active())).sorted(Comparator.comparingInt((Campaign c)->c.data().priority()).thenComparing(Campaign::id)).toList();require(campaigns.size()<=20,"Automatic execution supports at most 20 active campaigns");var offers=campaigns.stream().flatMap(c->c.data().offerIds().stream()).distinct().sorted().map(s::offer).toList();
        var request=new RunRequest(requestId,populationId,campaigns.stream().map(Campaign::id).toList());var evaluated=evaluate(s,runId,populationId,campaigns,offers,s.policy(),clock.instant());var preview=summary(runId,request,"PREVIEW",now(),null,null,s.policy(),campaigns,offers,evaluated);s.saveRun(preview);s.saveQualifications(runId,evaluated.rows());return finalizeIn(s,runId);
    });}
    /** Retrieves the requested marketing qualification run. */
    public Run run(String id){return store.read(s->found(s.marketingRun(id),"Run"));}
    /** Lists marketing qualification runs with bounded pagination. */
    public Page<RunSummary> runs(int offset,int limit){page(offset,limit);return store.read(s->s.marketingRuns(offset,limit));}
    /** Returns the qualification rows for a selected run. */
    public Page<Qualification> results(String id,int offset,int limit,String riskId){page(offset,limit);return store.read(s->{
        found(s.marketingRun(id),"Run");var rows=s.qualifications(id).stream().filter(q->riskId==null || q.riskId().equals(riskId)).toList();
        return new Page<>(rows.subList(Math.min(offset,rows.size()),Math.min(offset+limit,rows.size())),rows.size(),offset,limit);});}
    /** Lists qualification reservations and their retained history. */
    public List<Reservation> reservations(String id){return store.read(s->{found(s.marketingRun(id),"Run");return s.reservations().stream().filter(r->r.runId().equals(id)).toList();});}
    /** Carries candidate data for marketing engine operations. */
    private record Candidate(Customer customer,Member member,Decision decision,Campaign campaign,Offer offer,Suppression suppression,RuleInput input,List<String> businessReasons) {}
    /** Carries evaluation data for marketing engine operations. */
    private record Evaluation(List<Qualification> rows,int customers) {}
    /** Builds ordered qualification facts, invokes COBOL and applies published offer rules. */
    private Evaluation evaluate(Session s,String runId,String populationId,List<Campaign> campaigns,List<Offer> offers,Policy policy,Instant at){
        List<Member> members=new ArrayList<>();int offset=0;
        while(true){Page<Member> p=s.members(populationId,offset,1000);members.addAll(p.items());offset+=p.items().size();if(offset>=p.total())break;}
        var byCustomer=members.stream().collect(Collectors.groupingBy(Member::customerId,TreeMap::new,Collectors.toList()));
        int offersPerCustomer=campaigns.stream().mapToInt(c->c.data().offerIds().size()).sum();
        require((long)byCustomer.size()*offersPerCustomer<=100000,"Local run supports 100,000 customer/offer evaluations; select a smaller population or fewer campaigns");
        Map<String,Offer> offerMap=index(offers,Offer::id);LocalDate today=at.atZone(ZoneOffset.UTC).toLocalDate();
        List<Reservation> history=s.reservations().stream().filter(r->r.releasedAt()==null).toList();
        Map<String,List<Reservation>> byCustomerReservations=history.stream().collect(Collectors.groupingBy(Reservation::customerId));
        Map<String,Long> campaignCounts=history.stream().filter(r->active(r,at)).collect(Collectors.groupingBy(Reservation::campaignId,Collectors.counting()));
        List<Candidate> candidates=new ArrayList<>();int customerOrdinal=0;
        for(var entry:byCustomer.entrySet()){
            Customer customer=found(s.customer(entry.getKey()),"Customer");var data=customer.current().data();customerOrdinal++;
            Map<Product,Member> productMembers=index(entry.getValue(),Member::product);Suppression suppression=s.suppression(customer.id());
            List<Reservation> prior=byCustomerReservations.getOrDefault(customer.id(),List.of());
            Instant last=prior.stream().map(r->Instant.parse(r.createdAt())).max(Comparator.naturalOrder()).orElse(null);
            Long seconds=last==null?null:Math.max(0,Duration.between(last,at).getSeconds());
            int count=(int)prior.stream().filter(r->Instant.parse(r.createdAt()).isAfter(at.minus(Duration.ofDays(policy.data().rollingWindowDays())))).count();
            int slot=0;
            for(Campaign campaign:campaigns){slot++;var c=campaign.data();
                for(String offerId:c.offerIds().stream().sorted().toList()){
                    Offer offer=offerMap.get(offerId);var o=offer.data();Member member=productMembers.get(o.product());Decision decision=customer.latest(o.product());
                    var businessReasons=new ArrayList<String>();boolean uwEligible=decision!=null&&decision.status()==Status.ELIGIBLE;
                    if(offer.rules()!=null){if(published==null)throw new Problem(503,"Published COBOL rule evaluator unavailable");var u=published.underwriting(customer,offer);var m=published.marketing(customer,offer,suppressed(suppression,today));uwEligible=u.eligible()&&m.eligible();u.reasons().forEach(r->businessReasons.add("U:"+r));m.reasons().forEach(r->businessReasons.add("M:"+r));}
                    BigDecimal amount=switch(o.product()){case PERSONAL_LOAN->data.personalLoanAmountUsd();case CREDIT_CARD->data.requestedCardLimitUsd();case AUTO_LOAN->data.autoLoanAmountUsd();};
                    RuleInput input=new RuleInput(customerOrdinal,slot,member!=null,
                        member!=null && member.profileVersion()==customer.current().version() && decision!=null && member.decisionId().equals(decision.id()),
                        uwEligible,decision!=null && !date(decision.validUntil()).isBefore(today),
                        data.marketingOptIn(),data.prescreenOptOut(),suppressed(suppression,today),c.active(),inDate(c.startsOn(),c.endsOn(),today),o.active(),inDate(o.startsOn(),o.endsOn(),today),
                        data.bankingTenureMonths(),c.minimumTenureMonths(),data.existingProducts()==null?null:data.existingProducts().contains(o.product().name()),c.excludeExistingProduct(),
                        amount,o.minimumAmountUsd(),o.maximumAmountUsd(),seconds,count,campaignCounts.getOrDefault(campaign.id(),0L).intValue(),c.capacity(),policy.data().cooldownDays(),policy.data().maximumReservations(),
                        prior.stream().anyMatch(r->r.campaignId().equals(campaign.id()) && active(r,at)));
                    candidates.add(new Candidate(customer,member,decision,campaign,offer,suppression,input,List.copyOf(businessReasons)));
                }
            }
        }
        var answers=qualifier.evaluate(candidates.stream().map(Candidate::input).toList());
        if(answers.size()!=candidates.size())throw new IllegalStateException("Incomplete qualification response");
        List<Qualification> rows=new ArrayList<>();
        for(int i=0;i<candidates.size();i++){
            Candidate c=candidates.get(i);var answer=answers.get(i);
            var allReasons=new ArrayList<>(answer.reasons());allReasons.addAll(c.businessReasons());String ruleVersion=c.offer().rules()==null?null:com.lending.engine.simulation.application.PublishedPolicies.version(c.offer());
            String risk=stable(runId+"/"+c.customer().id());
            rows.add(new Qualification(stable(risk+"/"+c.campaign().id()+"/"+c.offer().id()),risk,c.customer().id(),c.customer().current().version(),
                c.member()==null?null:c.member().profileVersion(),c.member()==null?null:c.member().decisionId(),c.decision()==null?null:c.decision().id(),c.offer().data().product(),
                c.campaign().id(),c.campaign().version(),c.offer().id(),c.offer().version(),answer.qualified()?"QUALIFIED":"EXCLUDED",List.copyOf(allReasons),
                c.suppression()==null?null:c.suppression().version(),c.input().suppressed()?c.suppression().data().reason():null,ruleVersion!=null?ruleVersion:c.decision()==null?null:c.decision().policyVersion(),ruleVersion!=null?ruleVersion:answer.ruleVersion(),policy.version()));
        }
        return new Evaluation(List.copyOf(rows),byCustomer.size());
    }
    /** Aggregates qualification outcomes and reason counts for the run. */
    private static Run summary(String id,RunRequest request,String status,String created,String finalized,String cancelled,Policy policy,List<Campaign> campaigns,List<Offer> offers,Evaluation result){
        Set<String> customers=new HashSet<>(),reservations=new HashSet<>();Map<String,Long> reasons=new TreeMap<>();int qualified=0;
        for(Qualification q:result.rows()){if(q.outcome().equals("QUALIFIED")){qualified++;customers.add(q.customerId());reservations.add(q.customerId()+"/"+q.campaignId());}q.reasons().forEach(r->reasons.merge(r,1L,Long::sum));}
        return new Run(id,request,status,created,finalized,cancelled,policy,List.copyOf(campaigns),List.copyOf(offers),result.customers(),customers.size(),qualified,reservations.size(),reasons);
    }
    /** Creates the initial demonstration offers, campaigns and marketing policy when absent. */
    private void seed(){store.write(s->{
        if(s.policy()!=null)return null;
        String start=LocalDate.now(clock).toString(),end=LocalDate.now(clock).plusYears(1).toString();
        s.savePolicy(new Policy(1,Marketing.RULE_VERSION,now(),new PolicyInput(7,30,3,7)));
        Product[] products=Product.values();String[] codes={"PL","CC","AL"};String[] names={"Personal loan","Credit card","Auto loan"};
        for(int i=0;i<3;i++){
            int max=i==0?50000:i==1?25000:100000;
            for(int variant=1;variant<=2;variant++){
                String id="DEMO-"+codes[i]+"-"+variant;
                OfferInput data=new OfferInput(names[i]+" demo "+variant,products[i],true,start,end,new BigDecimal(variant==1?1000:5000),new BigDecimal(max),
                    new BigDecimal(i==0?"12.00":i==1?"21.00":"7.00"),BigDecimal.ZERO,i==1?0:36);
                s.saveOffer(new Offer(id,1,now(),data));
            }
            s.saveCampaign(new Campaign("DEMO-"+codes[i],1,now(),new CampaignInput(names[i]+" demonstration",true,start,end,(i+1)*10,1000,0,i==1,List.of("DEMO-"+codes[i]+"-1","DEMO-"+codes[i]+"-2"))));
        }
        return null;
    });}
    /** Returns the requested record or raises a not-found error. */
    private static <T> T found(T value,String name){if(value==null)throw new Problem(404,name+" not found");return value;}
    /** Indexes catalog records by identifier for qualification evaluation. */
    private static <K,V> Map<K,V> index(List<V> values,Function<V,K> key){return values.stream().collect(Collectors.toMap(key,Function.identity()));}
    /** Validates offset and page-size bounds for marketing queries. */
    private static void page(int offset,int limit){require(offset>=0 && offset<=10000000 && limit>=1 && limit<=200,"Use offset 0-10000000 and limit 1-200");}
    /** Derives a deterministic identifier from the supplied inputs. */
    private static String stable(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();}
    /** Converts a calendar date into the exclusive boundary after that day. */
    private static Instant endOfDay(String day){return date(day).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();}
    /** Returns the earlier of two validity boundaries. */
    private static Instant earlier(Instant a,Instant b){return a.isBefore(b)?a:b;}
    /** Returns the current application-clock time in serialized form. */
    private String now(){return clock.instant().toString();}
}

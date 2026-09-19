/**
 * Isolated experiment application: freeze drafts, run matched COBOL comparisons, analyze over local HTTP,
 * then explicitly publish reviewed versions.
 */
package com.lending.engine.simulation.application;
import com.lending.engine.simulation.domain.Simulation.*;
import com.lending.engine.simulation.domain.Simulation.Run;
import com.lending.engine.simulation.domain.Simulation.RunRequest;
import com.lending.engine.simulation.domain.Simulation.Population;
import com.lending.engine.simulation.domain.BusinessRules;
import com.lending.engine.simulation.domain.BusinessRules.*;
import com.lending.engine.simulation.infrastructure.*;
import com.lending.engine.application.Generator;
import com.lending.engine.domain.Model.*;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.marketing.application.MarketingEngine;
import com.lending.engine.marketing.domain.Marketing.*;
import com.lending.engine.marketing.domain.MarketingRules;
import com.lending.engine.infrastructure.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static com.lending.engine.domain.CustomerRules.require;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.hash;

/**
 * Isolated experiment application: freeze drafts, run matched COBOL comparisons, analyze over local HTTP,
 * then explicitly publish reviewed versions.
 */
public final class SimulationEngine implements AutoCloseable {
    public final SimulationStore store;private final MarketingEngine marketing;private final Clock clock;private final Path root;private final CobolBusinessPolicy policy;private final Function<Object,JsonNode> analysis;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Semaphore slots=new Semaphore(3);
    /** Initializes simulation engine with the supplied configuration and dependencies. */
    public SimulationEngine(SimulationStore store,MarketingEngine marketing,Path root,Clock clock,Function<Object,JsonNode> analysis){this.store=store;this.marketing=marketing;this.root=root;this.clock=clock;this.analysis=analysis;policy=new CobolBusinessPolicy(root);}
    /** Builds the local HTTP analysis adapter used by isolated Business experiments. */
    public static Function<Object,JsonNode> httpAnalysis(int port,String token){var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();return request->{try{var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/business-analysis")).timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(request))).build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()!=200)throw new Problem(503,"Local marketing analysis unavailable; no result published");return Json.MAPPER.readTree(r.body());}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}catch(java.io.IOException e){throw new Problem(503,"Local marketing analysis unavailable");}};}
    /** Creates an isolated reproducible synthetic population with independent bureau facts. */
    public synchronized Object generate(Generate request){require(request!=null&&request.count()>=60&&request.count()<=10000,"Generate 60-10000 synthetic customers");String id=UUID.randomUUID().toString(),asOf=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();var data=Generator.generate(request.count(),request.seed());var random=new Random(request.seed()^0x425552454155L);var people=new ArrayList<Person>();for(int i=0;i<data.size();i++){String cid=String.format(Locale.ROOT,"S%05d",i+1);int file=random.nextInt(100);var f=new Facts(file<4?FileStatus.NO_HIT:file<7?FileStatus.FROZEN:file<9?FileStatus.AMBIGUOUS:FileStatus.MATCHED,580+random.nextInt(271),BigDecimal.valueOf(200+random.nextInt(2601)),random.nextInt(101),random.nextInt(100)<80?0:random.nextInt(6),random.nextInt(9),random.nextInt(241),random.nextInt(100)<3,Instant.parse(asOf).minus(Duration.ofDays(random.nextInt(100)<3?45:0)).toString());people.add(new Person(cid,data.get(i),f,random.nextInt(100)<5));}var p=new Population(id,data.size(),request.seed(),asOf,"BUS-POP-1",List.copyOf(people));store.put("POPULATION",id,1,p,false);return summary(p);}
    /** Lists the available population snapshots. */
    public List<Object> populations(){return store.list("POPULATION",Population.class).stream().map(SimulationEngine::summary).toList();}
    /** Returns the saved characteristics of a simulation population. */
    private static Object summary(Population p){return Map.of("id",p.id(),"count",p.count(),"seed",p.seed(),"asOf",p.asOf(),"generatorVersion",p.generatorVersion());}
    /** Returns a page of synthetic customers from an isolated simulation population. */
    public Object people(String id,int offset,int limit){require(offset>=0&&limit>=1&&limit<=100,"Use offset >=0 and limit 1-100");var p=store.get("POPULATION",id,0,Population.class);return new Page<>(p.people().subList(Math.min(offset,p.count()),(int)Math.min((long)offset+limit,p.count())),p.count(),offset,limit);}
    /** Creates a versioned experiment draft from the current catalog and rule snapshots. */
    public synchronized Draft create(CreateDraft input){require(input!=null,"Draft required");name(input.name());var baseline=marketing.offer(input.baselineOfferId());var campaign=marketing.campaign(input.campaignId());require(campaign.data().offerIds().contains(baseline.id()),"Choose a campaign containing the baseline offer");String id=UUID.randomUUID().toString();RuleSet r=baseline.rules()==null?BusinessRules.defaults(baseline.data().product()):baseline.rules();var m=(ObjectNode)Json.MAPPER.valueToTree(r.marketing());m.put("minimumTenureMonths",campaign.data().minimumTenureMonths());m.put("excludeExistingProduct",campaign.data().excludeExistingProduct());r=new RuleSet("BR-"+id,1,clock.instant().toString(),r.underwriting(),Json.MAPPER.convertValue(m,Stage.class),r.bureau());var draft=new Draft(id,1,clock.instant().toString(),input.name().trim(),baseline,campaign,marketing.policy(),baseline.data(),r);store.put("DRAFT",id,1,draft,false);return draft;}
    /** Saves revised experiment forms only when their expected draft version matches. */
    public synchronized Draft edit(String id,EditDraft input){require(input!=null,"Draft edit required");var prior=store.get("DRAFT",id,0,Draft.class);if(input.expectedVersion()!=prior.version())throw new Problem(409,"Draft changed; reload the current version");name(input.name());MarketingRules.offer(input.offer());require(input.offer().product()==prior.baseline().data().product(),"Product must match baseline; create another draft");BusinessRules.validate(input.rules());int v=prior.version()+1;var r=input.rules();var next=new Draft(id,v,clock.instant().toString(),input.name().trim(),prior.baseline(),prior.campaign(),prior.policy(),input.offer(),new RuleSet(prior.rules().id(),v,clock.instant().toString(),r.underwriting(),r.marketing(),r.bureau()));store.put("DRAFT",id,v,next,false);return next;}
    /** Queues a frozen draft for a matched baseline-versus-candidate experiment. */
    public synchronized Run submit(RunRequest request){require(request!=null,"Run request required");var draft=store.get("DRAFT",request.draftId(),0,Draft.class);if(draft.version()!=request.draftVersion())throw new Problem(409,"Draft version changed");var population=store.get("POPULATION",request.populationId(),0,Population.class);if(!slots.tryAcquire())throw new Problem(429,"Three experiments are already queued or running");var run=new Run(UUID.randomUUID().toString(),request,draft,"QUEUED",clock.instant().toString(),null,null,null);try{store.put("RUN",run.id(),1,run,false);worker.submit(()->{try{execute(run,population);}finally{slots.release();}});}catch(RuntimeException e){slots.release();throw e;}return run;}
    /** Evaluates frozen facts through COBOL, requests local cohort analysis and saves the complete report. */
    private void execute(Run run,Population population){try{store.put("RUN",run.id(),1,new Run(run.id(),run.request(),run.draft(),"RUNNING",run.createdAt(),null,null,null),true);var d=run.draft();var baseline=outcomes(population,d.baseline().data(),d.baseline().rules(),d.campaign().data(),d.policy().data());var c=d.campaign().data();var proposedCampaign=new CampaignInput(c.name(),c.active(),c.startsOn(),c.endsOn(),c.priority(),c.capacity(),d.rules().marketing().minimumTenureMonths(),d.rules().marketing().excludeExistingProduct(),c.offerIds());var candidate=outcomes(population,d.offer(),d.rules(),proposedCampaign,d.policy().data());var samples=new ArrayList<Object>();for(int i=0;i<population.count();i++){var p=population.people().get(i);var f=(ObjectNode)Json.MAPPER.valueToTree(p.customer());f.retain("monthlyIncomeUsd","monthlyDebtPaymentsUsd","creditUtilizationPct","bankingTenureMonths","depositBalanceUsd","monthlySpendUsd");samples.add(Map.of("customerId",p.id(),"features",f,"bureauScore",p.bureau().creditScore(),"baselineTerms",terms(p,d.baseline().data()),"candidateTerms",terms(p,d.offer()),"baseline",baseline.get(i),"candidate",candidate.get(i)));}
        var result=analysis.apply(Map.of("populationId",population.id(),"seed",run.request().seed(),"asOf",population.asOf(),"product",d.offer().product().name(),"rows",samples));if(result.path("rows").size()!=population.count()||!result.path("report").isObject()||!result.path("model").isObject())throw new IllegalStateException("Incomplete analysis result");var rows=new ArrayList<Row>();for(int i=0;i<population.count();i++){var score=result.path("rows").get(i);if(!score.path("customerId").asText().equals(population.people().get(i).id()))throw new IllegalStateException("Analysis correlation failed");rows.add(new Row(population.people().get(i).id(),baseline.get(i),candidate.get(i),score));}var report=(ObjectNode)result.path("report").deepCopy();report.set("modelArtifact",result.path("model"));report.put("populationHash",hash(population));report.put("draftHash",hash(d));report.put("asOf",population.asOf());report.put("populationId",population.id());store.complete(new Run(run.id(),run.request(),d,"COMPLETED",run.createdAt(),clock.instant().toString(),null,report),rows);
    }catch(Exception e){store.put("RUN",run.id(),1,new Run(run.id(),run.request(),run.draft(),"FAILED",run.createdAt(),clock.instant().toString(),e instanceof Problem?e.getMessage():"Simulation runtime failed; check COBOL and local marketing service; no publication occurred",null),true);}}
    /** Evaluates matched customer and bureau facts through the underwriting, marketing and bureau stages. */
    private List<Outcome> outcomes(Population p,OfferInput offer,RuleSet rules,CampaignInput campaign,PolicyInput global){int n=p.count();var u=new ArrayList<StageResult>();var mRules=new ArrayList<StageResult>();var b=new ArrayList<StageResult>();Instant at=Instant.parse(p.asOf());LocalDate today=at.atZone(ZoneOffset.UTC).toLocalDate();
        if(rules==null){var old=new CobolUnderwriter(root.resolve("build/liuwbatch"),root.resolve("runtime/scratch")).evaluate(p.people().stream().map(Person::customer).toList());for(var rs:old){var r=rs.get(offer.product().ordinal());u.add(new StageResult(r.status().name(),r.reasons()));mRules.add(new StageResult("ELIGIBLE",List.of()));}}
        else{var requests=new ArrayList<String>();for(var person:p.people()){requests.add(CobolBusinessPolicy.customerRecord(person.customer(),offer.product(),rules.underwriting(),"U",person.suppressed()));requests.add(CobolBusinessPolicy.customerRecord(person.customer(),offer.product(),rules.marketing(),"M",person.suppressed()));requests.add(CobolBusinessPolicy.bureauRecord(credit(person,offer,rules),person.bureau(),rules.bureau(),at));}var answers=policy.evaluate(requests);for(int i=0;i<n;i++){u.add(stage(answers.get(i*3)));mRules.add(stage(answers.get(i*3+1)));b.add(stage(answers.get(i*3+2)));}}
        var inputs=new ArrayList<RuleInput>();for(int i=0;i<n;i++){var person=p.people().get(i);var data=person.customer();boolean eligible=u.get(i).eligible()&&mRules.get(i).eligible();inputs.add(new RuleInput(i+1,1,u.get(i).eligible(),true,eligible,true,data.marketingOptIn(),data.prescreenOptOut(),person.suppressed(),campaign.active(),inDate(campaign.startsOn(),campaign.endsOn(),today),offer.active(),inDate(offer.startsOn(),offer.endsOn(),today),data.bankingTenureMonths(),campaign.minimumTenureMonths(),data.existingProducts()==null?null:data.existingProducts().contains(offer.product().name()),campaign.excludeExistingProduct(),CobolBusinessPolicy.amount(data,offer.product()),offer.minimumAmountUsd(),offer.maximumAmountUsd(),null,0,0,campaign.capacity(),global.cooldownDays(),global.maximumReservations(),false));}
        var marketingResults=new com.lending.engine.marketing.infrastructure.CobolMarketingQualifier(root.resolve("build/limkbatch"),root.resolve("runtime/scratch")).evaluate(inputs);
        if(rules==null)try(var credit=new com.lending.engine.credit.CobolCreditPolicy(root.resolve("build/licbbatch"),1,Clock.fixed(at,ZoneOffset.UTC))){for(int i=0;i<n;i++){if(!marketingResults.get(i).qualified()){b.add(new StageResult("NOT_REACHED",List.of()));continue;}var person=p.people().get(i);var result=credit.evaluate(credit(person,offer,null),new BureauProfile(person.id(),person.id(),1,"SYNTHETIC",p.asOf(),person.bureau()));b.add(new StageResult(result.outcome().equals("APPROVED")?"ELIGIBLE":result.outcome().equals("REVIEW")?"INCOMPLETE":"INELIGIBLE",result.reasons()));}}
        var out=new ArrayList<Outcome>();for(int i=0;i<n;i++){var result=marketingResults.get(i);var reasons=new ArrayList<>(result.reasons());reasons.addAll(mRules.get(i).reasons());out.add(new Outcome(u.get(i),new StageResult(result.qualified()?"ELIGIBLE":"INELIGIBLE",List.copyOf(reasons)),result.qualified()?b.get(i):new StageResult("NOT_REACHED",List.of())));}return out;
    }
    /** Converts a COBOL stage result into the simulation outcome vocabulary. */
    private static StageResult stage(BusinessRules.Result r){return new StageResult(r.status(),r.reasons());}
    /** Checks whether the supplied date falls within the configured validity interval. */
    private static boolean inDate(String start,String end,LocalDate day){return !day.isBefore(LocalDate.parse(start))&&!day.isAfter(LocalDate.parse(end));}
    /** Builds the financial terms used for a baseline or candidate comparison. */
    private static Map<String,Object> terms(Person p,OfferInput o){BigDecimal amount=CobolBusinessPolicy.amount(p.customer(),o.product());return Map.of("amountUsd",amount==null||amount.signum()==0?BigDecimal.ONE:amount,"aprPct",o.illustrativeAprPct(),"annualFeeUsd",o.annualFeeUsd(),"termMonths",o.termMonths());}
    /** Builds independent synthetic credit evidence for the isolated experiment. */
    private static CreditInput credit(Person p,OfferInput o,RuleSet rules){return new CreditInput(p.id(),p.id(),p.id(),o.product(),CobolBusinessPolicy.amount(p.customer(),o.product()),p.customer().monthlyIncomeUsd(),"SELF_REPORTED",p.customer().vehicleValueUsd(),o.termMonths(),o.illustrativeAprPct(),rules);}
    /**
     * Publishes a reviewed completed experiment only when its tested draft and source catalog remain
     * current.
     */
    public synchronized Publication publish(String id,Review review){require(review!=null&&review.confirm(),"Explicit Business review and confirmation required");require(review.note()!=null&&review.note().trim().length()>=10&&review.note().length()<=1000,"Write a review note of 10-1000 characters explaining the decision");var run=store.get("RUN",id,0,Run.class);if(!run.status().equals("COMPLETED"))throw new Problem(409,"Only completed comparisons can be published");String fingerprint=hash(List.of(run.id(),run.draft(),run.report(),review.note().trim()));var prior=marketing.publication(id);if(prior!=null){if(!prior.fingerprint().equals(fingerprint))throw new Problem(409,"Publication review differs from the committed receipt");store.put("PUBLICATION",id,1,prior,true);return prior;}var draft=store.get("DRAFT",run.draft().id(),0,Draft.class);if(draft.version()!=run.draft().version())throw new Problem(409,"Draft changed since this result; run the new version first");var receipt=marketing.publish(run,fingerprint,review.note().trim());store.put("PUBLICATION",id,1,receipt,true);return receipt;}
    /** Builds the offer-catalog snapshot used to compare and publish simulation drafts. */
    public Object catalog(){return Map.of("offers",marketing.offers(),"campaigns",marketing.campaigns());}
    /** Validates a user-visible simulation name. */
    private static void name(String name){require(name!=null&&!name.isBlank()&&name.length()<=60,"Use a name of 1-60 characters");}
    /** Releases the resources owned by this component. */
    public void close(){worker.shutdownNow();try{worker.awaitTermination(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}store.close();}
}

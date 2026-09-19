/**
 * Stateless synthetic offer comparison reuses train-only K-means and customer-fit scoring without changing
 * active models or offers.
 */
package com.lending.offers.domain;
import com.fasterxml.jackson.databind.JsonNode;
import com.lending.offers.Json;
import static com.lending.offers.domain.Model.*;
import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * Stateless synthetic offer comparison reuses train-only K-means and customer-fit scoring without changing
 * active models or offers.
 */
public final class BusinessAnalysis {
    /** Prevents instantiation of this utility-only type. */
    private BusinessAnalysis(){}
    /** Carries input data for business analysis operations. */
    public record Input(String populationId,long seed,String asOf,String product,List<Sample> rows) {}
    /** Carries sample data for business analysis operations. */
    public record Sample(String customerId,JsonNode features,int bureauScore,Terms baselineTerms,Terms candidateTerms,JsonNode baseline,JsonNode candidate) {}
    /** Carries scored data for business analysis operations. */
    public record Scored(String customerId,int cohort,String partition,Metrics baseline,Metrics candidate,boolean baselineEligible,boolean candidateEligible,double bureauScore,double debtToIncome,JsonNode baselineOutcome,JsonNode candidateOutcome) {}
    /** Carries output data for business analysis operations. */
    public record Output(CohortModel model,Map<String,Object> report,List<Scored> rows) {}
    /**
     * Fits an isolated cohort model and compares baseline and candidate offers on the same synthetic
     * customers.
     */
    public static Output analyze(Input input){
        if(input==null||input.rows()==null||input.rows().size()<60||input.rows().size()>10000||!Set.of("PERSONAL_LOAN","CREDIT_CARD","AUTO_LOAN").contains(input.product()))throw new Json.Fault(422,"Use one product and 60-10000 synthetic rows");
        var seen=new HashSet<String>();var features=new ArrayList<JsonNode>();for(var row:input.rows()){if(row.customerId()==null||!seen.add(row.customerId())||row.features()==null||!row.features().isObject())throw new Json.Fault(422,"Distinct synthetic customer features required");valid(row.baselineTerms(),input.product());valid(row.candidateTerms(),input.product());var f=row.features().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)f).put("customerId",row.customerId());features.add(f);}
        var trained=Cohorts.train("BUS-"+Json.hash(input).substring(0,24),new Training(input.populationId(),input.seed()),features);
        var model=new CohortModel(trained.id(),trained.batchId(),trained.seed(),trained.algorithm(),trained.features(),trained.means(),trained.scales(),trained.centroids(),trained.preferences(),trained.metrics(),trained.datasetHash(),input.asOf());
        var ordered=new ArrayList<>(seen);Collections.sort(ordered);Collections.shuffle(ordered,new Random(input.seed()));var partition=new HashMap<String,String>();int n=ordered.size(),train=(int)(n*.6),val=(int)(n*.2);for(int i=0;i<n;i++)partition.put(ordered.get(i),i<train?"TRAIN":i<train+val?"VALIDATION":"TEST");
        var scored=new ArrayList<Scored>();for(var s:input.rows()){var weights=Cohorts.weights(model,s.features());double income=Cohorts.n(s.features(),"monthlyIncomeUsd",0);scored.add(new Scored(s.customerId(),Cohorts.cohort(model,s.features()),partition.get(s.customerId()),Personalizer.score(s.baselineTerms(),s.baselineTerms(),input.product(),s.features(),weights),Personalizer.score(s.candidateTerms(),s.baselineTerms(),input.product(),s.features(),weights),eligible(s.baseline()),eligible(s.candidate()),s.bureauScore(),income>0?100*Cohorts.n(s.features(),"monthlyDebtPaymentsUsd",0)/income:Double.NaN,s.baseline(),s.candidate()));}
        var report=new LinkedHashMap<String,Object>();report.put("overall",summarize(scored));report.put("heldOutTest",summarize(scored.stream().filter(s->s.partition().equals("TEST")).toList()));var groups=new TreeMap<String,Object>();for(int c=0;c<model.centroids().length;c++){int selected=c;groups.put("Cohort "+c,summarize(scored.stream().filter(s->s.cohort()==selected).toList()));}report.put("cohorts",groups);report.put("model",model.metrics());report.put("scoringVersion",Personalizer.SCORING);report.put("costHorizon",input.product().equals("CREDIT_CARD")?"One year at full utilization; revolving balance repayment is not forecast":"Contract term; interest plus annual fees pro-rated by term");
        report.put("assumptions",List.of("Synthetic customers and independent synthetic bureau facts; no real customer outcomes.","Both offers use the same customers, bureau facts, date, seed and K-means model.","No existing reservations; campaign capacity is applied in deterministic customer order separately to each offer. Cross-campaign competition is not modeled.","Acceptance uses an uncalibrated utility curve, not observed conversion. No default, loss, profit or fairness estimate is available.","Fit/payment/cost summaries use eligible customers; paired changes use only customers eligible for both. Missing inputs are excluded by qualification, not treated as evidence of eligibility.","95% Wilson intervals describe synthetic eligibility sampling only, not model uncertainty or real-world confidence.","Small groups (<30) are unstable. Test data never select K or fit scalers. Silhouette evaluates a bounded 200-row sample per partition.","New offer publication does not deploy this experimental cohort model or change existing offers."));
        return new Output(model,report,List.copyOf(scored));
    }
    /** Rejects comparison terms outside the supported product and financial ranges. */
    private static void valid(Terms t,String product){if(t==null||t.amountUsd().signum()<=0||t.aprPct().signum()<0||t.aprPct().doubleValue()>100||t.annualFeeUsd().signum()<0||product.equals("CREDIT_CARD")&&t.termMonths()!=0||!product.equals("CREDIT_CARD")&&(t.termMonths()<1||t.termMonths()>120))throw new Json.Fault(422,"Invalid comparison terms");}
    /** Requires all three qualification stages to report eligibility for a comparison row. */
    private static boolean eligible(JsonNode n){return n!=null&&List.of("underwriting","marketing","bureau").stream().allMatch(k->n.path(k).path("status").asText().equals("ELIGIBLE"));}
    /** Summarizes eligibility gains, losses and paired financial changes for a population or cohort. */
    private static Map<String,Object> summarize(List<Scored> rows){var out=new LinkedHashMap<String,Object>();int n=rows.size();out.put("population",n);out.put("smallSample",n<30);out.put("baseline",side(rows,false));out.put("candidate",side(rows,true));out.put("newlyEligible",rows.stream().filter(r->!r.baselineEligible()&&r.candidateEligible()).count());out.put("lostEligibility",rows.stream().filter(r->r.baselineEligible()&&!r.candidateEligible()).count());var paired=rows.stream().filter(r->r.baselineEligible()&&r.candidateEligible()).toList();var delta=new LinkedHashMap<String,Object>();delta.put("count",paired.size());delta.put("fitPoints",distribution(paired,r->r.candidate().fitScore()-r.baseline().fitScore()));delta.put("monthlyPaymentUsd",distribution(paired,r->r.candidate().monthlyPaymentUsd()-r.baseline().monthlyPaymentUsd()));delta.put("costUsd",distribution(paired,r->r.candidate().totalCostUsd()-r.baseline().totalCostUsd()));out.put("pairedChanges",delta);return out;}
    /**
     * Aggregates one offer side, including its qualification funnel, exclusions and assumed acceptance
     * sensitivity.
     */
    private static Map<String,Object> side(List<Scored> all,boolean candidate){var rows=all.stream().filter(s->candidate?s.candidateEligible():s.baselineEligible()).toList();var out=new LinkedHashMap<String,Object>();int n=all.size(),yes=rows.size();out.put("eligible",yes);out.put("eligibilityPct",n==0?null:100d*yes/n);out.put("eligibility95Pct",wilson(yes,n));var counts=new TreeMap<String,Integer>();int u=0,m=0,incomplete=0;for(var s:all){var result=candidate?s.candidateOutcome():s.baselineOutcome();boolean ue=result.path("underwriting").path("status").asText().equals("ELIGIBLE");if(ue)u++;if(ue&&result.path("marketing").path("status").asText().equals("ELIGIBLE"))m++;boolean missing=false;for(String stage:List.of("underwriting","marketing","bureau")){var a=result.path(stage);if(a.path("status").asText().equals("INCOMPLETE"))missing=true;for(var reason:a.path("reasons"))counts.merge(stage+":"+reason.asText(),1,Integer::sum);}if(missing)incomplete++;}out.put("underwritingPassed",u);out.put("marketingPassed",m);out.put("bureauPassedAfterMarketing",yes);out.put("incompleteOrReview",incomplete);out.put("reasonsMayOverlap",counts);
        out.put("fitPoints",distribution(rows,s->metric(s,candidate).fitScore()));out.put("monthlyPaymentUsd",distribution(rows,s->metric(s,candidate).monthlyPaymentUsd()));out.put("costUsd",distribution(rows,s->metric(s,candidate).totalCostUsd()));out.put("bureauScore",distribution(rows,Scored::bureauScore));out.put("selfReportedDtiPct",distribution(rows,Scored::debtToIncome));out.put("simulatedExpectedAcceptances",rows.stream().mapToDouble(s->metric(s,candidate).simulatedAcceptance()).sum());var sensitivity=new LinkedHashMap<String,Double>();for(int shift:new int[]{-10,0,10})sensitivity.put(shift==-10?"conservative":shift==0?"base":"optimistic",rows.stream().mapToDouble(s->1/(1+Math.exp(-(metric(s,candidate).fitScore()-65+shift)/12))).sum());out.put("acceptanceSensitivityAssumedUtilityShift10",sensitivity);return out;
    }
    /** Selects the baseline or candidate metrics for a paired simulation row. */
    private static Metrics metric(Scored s,boolean candidate){return candidate?s.candidate():s.baseline();}
    /** Summarizes finite values with a count, mean, median and 90th percentile. */
    private static Map<String,Object> distribution(List<Scored> rows,ToDoubleFunction<Scored> value){double[] a=rows.stream().mapToDouble(value).filter(Double::isFinite).sorted().toArray();var m=new LinkedHashMap<String,Object>();m.put("n",a.length);m.put("mean",a.length==0?null:Arrays.stream(a).average().orElseThrow());m.put("p50",a.length==0?null:a[(a.length-1)/2]);m.put("p90",a.length==0?null:a[(int)Math.ceil(a.length*.9)-1]);return m;}
    /** Calculates a 95 percent Wilson interval for the synthetic eligibility proportion. */
    private static Object wilson(int yes,int n){if(n==0)return null;double p=(double)yes/n,z=1.96,z2=z*z,mid=(p+z2/(2*n))/(1+z2/n),radius=z*Math.sqrt(p*(1-p)/n+z2/(4d*n*n))/(1+z2/n);return List.of(100*Math.max(0,mid-radius),100*Math.min(1,mid+radius));}
}

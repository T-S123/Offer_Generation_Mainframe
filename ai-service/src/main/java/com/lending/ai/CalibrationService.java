package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;

/** Versioned regularized probability calibration, gated on real mature outcomes and disjoint temporal publication groups. */
public final class CalibrationService {
    private final Store store;private final int followUpDays;
    public CalibrationService(Store store,int days){this.store=store;followUpDays=days;}
    record Label(String publication,String customer,String eligibleAt,double probability,int selected) {}
    private List<JsonNode> all(String kind){var out=new ArrayList<JsonNode>();String after="";for(;;){var page=store.list(kind,after,100);page.forEach(r->out.add(r.data()));if(page.size()<100)break;after=page.get(page.size()-1).id();}return out;}
    /** Builds labels only when features predate eligibility and the full selection opportunity has matured. */
    List<Label> labels(String product,String context){
        var features=new HashMap<String,JsonNode>();for(var f:all("FORECAST_FEATURE"))if(f.path("sourceMode").asText().equals("OBSERVED_REAL")&&f.at("/data/product").asText().equals(product)&&f.at("/data/policyContext").asText().equals(context))features.put(f.path("catalogOfferId").asText()+":"+f.path("customerKey").asText(),f);
        var selections=all("SELECTION");var result=new ArrayList<Label>();Instant now=Instant.now();
        for(var e:all("ENROLLMENT")){
            if(!e.path("sourceMode").asText().equals("OBSERVED_REAL"))continue;String pub=e.path("catalogOfferId").asText(),customer=e.path("customerKey").asText();
            var feature=features.get(pub+":"+customer);if(feature==null)continue;
            Instant at=Instant.parse(e.path("eligibleAt").asText()),end=Instant.parse(e.path("followUpEndsAt").asText());
            if(end.plus(Duration.ofHours(24)).isAfter(now)||Instant.parse(feature.at("/data/featureAsOf").asText()).isAfter(at))continue;
            var watermark=store.find("SOURCE_WATERMARK",hash(List.of("OBSERVED_REAL",pub)));
            if(watermark.isEmpty()||!watermark.get().data().at("/data/complete").asBoolean()||Instant.parse(watermark.get().data().at("/data/through").asText()).isBefore(end.plus(Duration.ofHours(24))))continue;
            int y=0;for(var s:selections)if(s.path("catalogOfferId").asText().equals(pub)&&s.path("customerKey").asText().equals(customer)&&s.path("sourceMode").asText().equals("OBSERVED_REAL")){
                Instant selected=Instant.parse(s.path("selectedAt").asText());if(!selected.isBefore(at)&&!selected.isAfter(end)){y=1;break;}
            }
            result.add(new Label(pub,customer,at.toString(),feature.at("/data/baseProbability").asDouble(),y));
            require(result.size()<=20000,"CALIBRATION_LIMIT","Limit a calibration dataset to 20,000 eligible subjects");
        }
        return result.stream().sorted(Comparator.comparing(Label::eligibleAt).thenComparing(Label::publication).thenComparing(Label::customer)).toList();
    }
    public JsonNode readiness(String product,String context){var labels=labels(product,context);return obj("sourceMode","OBSERVED_REAL","matureLabels",labels.size(),"publications",labels.stream().map(Label::publication).distinct().count(),"status",labels.size()<500?"INSUFFICIENT_REAL_LABELS":"EVALUATION_REQUIRED","predictionKind","SIMULATED_UTILITY_UNTIL_ACTIVATED");}
    /** Fits on earlier publications and evaluates untouched later publications; no demo record enters training. */
    public JsonNode train(String analyst,JsonNode body){
        fields(body,"product","policyContext");String product=text(body,"product"),context=text(body,"policyContext");var data=labels(product,context);
        require(data.size()>=500,"INSUFFICIENT_REAL_LABELS","At least 500 mature real labels are required");
        var groups=new LinkedHashSet<String>();data.forEach(l->groups.add(l.publication()));require(groups.size()>=5,"TEMPORAL_SUPPORT","At least five independent publication cohorts are required");
        var publications=new ArrayList<>(groups);int trainEnd=(int)(publications.size()*.6),validationEnd=(int)(publications.size()*.8);
        Set<String> trainIds=new HashSet<>(publications.subList(0,trainEnd)),validationIds=new HashSet<>(publications.subList(trainEnd,validationEnd));
        var train=data.stream().filter(l->trainIds.contains(l.publication())).toList();var validation=data.stream().filter(l->validationIds.contains(l.publication())).toList();var test=data.stream().filter(l->!trainIds.contains(l.publication())&&!validationIds.contains(l.publication())).toList();
        require(train.size()>=100&&validation.size()>=50&&test.size()>=50,"TEMPORAL_SUPPORT","Each temporal split needs sufficient support");
        require(train.get(train.size()-1).eligibleAt().compareTo(validation.get(0).eligibleAt())<0&&validation.get(validation.size()-1).eligibleAt().compareTo(test.get(0).eligibleAt())<0,"TEMPORAL_OVERLAP","Publication cohorts overlap in time; supply separate completed cohorts");
        var seen=new HashSet<String>();for(var row:data)require(seen.add(row.customer()),"CUSTOMER_LEAKAGE","A customer appears in more than one publication or label");
        for(var split:List.of(train,validation,test)){long yes=split.stream().filter(l->l.selected()==1).count();require(yes>=20&&split.size()-yes>=20,"CLASS_SUPPORT","Each split needs at least 20 selections and 20 non-selections");}
        double[] coefficients=fit(train);double baseRate=train.stream().mapToInt(Label::selected).average().orElseThrow();
        var validationMetrics=metrics(validation,coefficients,baseRate);var testMetrics=metrics(test,coefficients,baseRate);
        boolean pass=validationMetrics.path("brier").asDouble()<validationMetrics.path("constantBrier").asDouble()
            &&testMetrics.path("brier").asDouble()<testMetrics.path("constantBrier").asDouble()
            &&validationMetrics.path("ece").asDouble()<=.08&&testMetrics.path("ece").asDouble()<=.08;
        String dataHash=hash(data),id=hash(List.of(dataHash,product,context,"platt-ridge-v1"));
        var model=obj("id",id,"analyst",analyst,"status",pass?"EVALUATED_PASS":"EVALUATED_FAIL","sourceMode","OBSERVED_REAL","scope","OFFER_FAMILY",
            "product",product,"policyContext",context,"followUpDays",followUpDays,"intercept",coefficients[0],"slope",coefficients[1],"datasetHash",dataHash,
            "trainingLabels",train.size(),"validation",validationMetrics,"test",testMetrics,"createdAt",Instant.now().toString(),
            "limitations",List.of("Observational calibration estimates selection, not causal uplift.","Applicable only to the evaluated product, follow-up and choice-set policy context.","No underwriting rule or existing prediction is modified."));
        if(store.find("CALIBRATOR",id).isEmpty())store.create("CALIBRATOR",id,model);return store.get("CALIBRATOR",id).data();
    }
    /** Activates only an evaluated passing model after exact analyst review; choosing a prior passing model is rollback. */
    public synchronized JsonNode activate(String analyst,JsonNode body){
        fields(body,"modelId","datasetHash","confirm","note");var model=store.get("CALIBRATOR",text(body,"modelId")).data();
        require(model.path("analyst").asText().equals(analyst)&&model.path("status").asText().equals("EVALUATED_PASS"),"MODEL_GATE","A passing analyst-owned evaluation is required");
        require(body.path("confirm").asBoolean()&&text(body,"datasetHash").equals(model.path("datasetHash").asText())&&text(body,"note").length()>=10,"MODEL_APPROVAL","Confirm the exact evaluation with a review note");
        String key=hash(List.of(model.path("product"),model.path("policyContext"),followUpDays));var prior=store.find("ACTIVE_CALIBRATOR",key);
        var change=obj("modelId",model.path("id"),"analyst",analyst,"note",body.path("note"),"activatedAt",Instant.now().toString(),"previous",prior.map(Store.Saved::data).orElse(null));
        store.create("MODEL_ACTIVATION",id(),change);if(prior.isEmpty())store.create("ACTIVE_CALIBRATOR",key,change);else store.update("ACTIVE_CALIBRATOR",key,prior.get().version(),change);return change;
    }
    /** Prospective forecasts require the exact evaluated policy context and pre-decision subject probabilities. */
    public JsonNode forecast(String product,String context,List<Double> probabilities){
        require(!probabilities.isEmpty()&&probabilities.size()<=20000,"FORECAST_SUPPORT","Supply a bounded prospective eligible cohort");
        String key=hash(List.of(tree(product),tree(context),followUpDays));var active=store.find("ACTIVE_CALIBRATOR",key);
        if(active.isEmpty())return obj("status","NO_ACTIVE_CALIBRATION","estimatePct",null);
        var model=store.get("CALIBRATOR",active.get().data().path("modelId").asText()).data();double total=0;
        for(Double p:probabilities){require(p!=null&&Double.isFinite(p)&&p>=0&&p<=1,"INVALID_PROBABILITY","Probabilities must be finite and between zero and one");total+=sigmoid(model.path("intercept").asDouble()+model.path("slope").asDouble()*logit(p));}
        return obj("status","AVAILABLE","predictionKind","CALIBRATED_SELECTION","scope","OFFER_FAMILY","sourceMode","OBSERVED_REAL","modelId",model.path("id"),"datasetHash",model.path("datasetHash"),"estimatePct",100*total/probabilities.size(),"eligible",probabilities.size(),"followUpDays",followUpDays,"product",product,"policyContext",context,"generatedAt",Instant.now().toString());
    }
    /** Freezes a prospective forecast before enrollment; simulated predictions remain unchanged in their original receipt. */
    public synchronized JsonNode freezeForecast(String publication,String analyst,JsonNode body){
        fields(body,"product","policyContext");var pub=store.get("PUBLICATION",publication).data();
        require(pub.path("analyst").asText().equals(analyst),"MODEL_APPROVAL","Publication belongs to another analyst");
        String product=text(body,"product"),context=text(body,"policyContext");
        require(pub.at("/preview/configuration/offer/product").asText().equals(product),"FORECAST_CONTEXT","Product differs from the published offer");
        var prior=store.find("REAL_PREDICTION",publication);if(prior.isPresent()){require(prior.get().data().path("product").asText().equals(product)&&prior.get().data().path("policyContext").asText().equals(context),"FORECAST_FROZEN","Forecast context cannot change");return prior.get().data();}
        require(all("ENROLLMENT").stream().noneMatch(e->e.path("catalogOfferId").asText().equals(publication)&&e.path("sourceMode").asText().equals("OBSERVED_REAL")),"FORECAST_LEAKAGE","Freeze the forecast before any real enrollment");
        var features=all("FORECAST_FEATURE").stream().filter(e->e.path("catalogOfferId").asText().equals(publication)&&e.path("sourceMode").asText().equals("OBSERVED_REAL")&&e.at("/data/product").asText().equals(product)&&e.at("/data/policyContext").asText().equals(context)).toList();
        require(!features.isEmpty(),"FORECAST_SUPPORT","The operational source must supply pre-decision prospective features");
        var forecast=(com.fasterxml.jackson.databind.node.ObjectNode)forecast(product,context,features.stream().map(e->e.at("/data/baseProbability").asDouble()).toList());
        require(forecast.path("status").asText().equals("AVAILABLE"),"MODEL_GATE","No evaluated calibration is active for this context");
        forecast.put("publicationId",publication).put("analyst",analyst).put("eligibleCohortHash",hash(features.stream().map(e->e.path("customerKey").asText()).sorted().distinct().toList()));
        forecast.put("featuresHash",hash(features));return store.create("REAL_PREDICTION",publication,forecast).data();
    }
    static double[] fit(List<Label> rows){double a=0,b=1;for(int i=0;i<1500;i++){double da=0,db=0;for(var r:rows){double x=logit(r.probability()),e=sigmoid(a+b*x)-r.selected();da+=e;db+=e*x;}a-=.05*da/rows.size();b-=.05*(db/rows.size()+.002*b);}return new double[]{a,b};}
    static JsonNode metrics(List<Label> rows,double[] weights,double constant){double brier=0,loss=0,baseline=0;double[][] bins=new double[10][3];for(var r:rows){double p=sigmoid(weights[0]+weights[1]*logit(r.probability()));brier+=Math.pow(p-r.selected(),2);baseline+=Math.pow(constant-r.selected(),2);loss-=r.selected()*Math.log(Math.max(p,1e-9))+(1-r.selected())*Math.log(Math.max(1-p,1e-9));int bin=Math.min(9,(int)(p*10));bins[bin][0]++;bins[bin][1]+=p;bins[bin][2]+=r.selected();}double ece=0;for(var bin:bins)if(bin[0]>0)ece+=Math.abs(bin[1]-bin[2])/rows.size();return obj("n",rows.size(),"brier",brier/rows.size(),"logLoss",loss/rows.size(),"ece",ece,"constantBrier",baseline/rows.size());}
    private static double logit(double p){p=Math.max(.0001,Math.min(.9999,p));return Math.log(p/(1-p));}
    private static double sigmoid(double x){return 1/(1+Math.exp(-Math.max(-30,Math.min(30,x))));}
}

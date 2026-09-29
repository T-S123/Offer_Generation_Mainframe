package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;

/** Reads all eight engine stages through owner APIs and releases only bounded, supported aggregate evidence to agents. */
public final class Diagnostics {
    private final Store store;private final EngineGateway engine;
    public Diagnostics(Store store,EngineGateway engine){this.store=store;this.engine=engine;}
    private static void increment(Map<String,Integer> counts,String key){if(!key.isBlank())counts.merge(key,1,Integer::sum);}
    private static JsonNode supported(Map<String,Integer> counts,int support){var n=object();counts.forEach((k,v)->{if(v>=support)n.put(k,v);});return n;}
    /** Historical profiles and selections use the eligibility version and follow-up window; package status is a current snapshot. */
    public JsonNode collect(String catalog,String mode,int minimumSupport){
        require(minimumSupport>=5,"SMALL_COHORT","Diagnostic cell support must be at least five");
        var stageCounts=new TreeMap<String,Map<String,Integer>>();for(int i=1;i<=8;i++)stageCounts.put("step"+i,new TreeMap<>());
        var segments=new TreeMap<String,int[]>();var failures=new TreeMap<String,Integer>();var runIds=new TreeSet<String>();
        var enrollments=new ArrayList<JsonNode>();String after="";boolean truncated=false;
        outer:for(;;){var page=store.list("ENROLLMENT",after,100);for(var row:page)if(row.data().path("catalogOfferId").asText().equals(catalog)&&row.data().path("sourceMode").asText().equals(mode)){
            if(enrollments.size()==128){truncated=true;break outer;}enrollments.add(row.data());
        }if(page.size()<100)break;after=page.get(page.size()-1).id();}
        Map<String,List<Instant>> selections=new HashMap<>();after="";
        for(;;){var page=store.list("SELECTION",after,100);for(var row:page)if(row.data().path("catalogOfferId").asText().equals(catalog)&&row.data().path("sourceMode").asText().equals(mode))selections.computeIfAbsent(row.data().path("customerKey").asText(),k->new ArrayList<>()).add(Instant.parse(row.data().path("selectedAt").asText()));if(page.size()<100)break;after=page.get(page.size()-1).id();}
        int reads=0;
        for(var e:enrollments){
            String customer=e.path("customerKey").asText();Instant start=Instant.parse(e.path("eligibleAt").asText()),end=Instant.parse(e.path("followUpEndsAt").asText());
            boolean hasSelection=selections.getOrDefault(customer,List.of()).stream().anyMatch(at->!at.isBefore(start)&&!at.isAfter(end)&&!at.isAfter(Instant.now()));
            var storage=store.find("OUTCOME_EVENT",e.path("sourceEventId").asText()).map(r->r.data().path("offer")).orElse(obj());
            increment(stageCounts.get("step5"),"personalization:"+storage.path("modelId").asText("unavailable"));
            increment(stageCounts.get("step6"),"historical-enrollment");
            increment(stageCounts.get("step8"),hasSelection?"selection-recorded":"no-recorded-selection");
            if(!mode.equals("DEMO"))continue;
            try{
                var response=engine.get("decision-responses/"+e.path("offerFamilyId").asText());reads++;
                increment(stageCounts.get("step3"),response.path("bureauDecision").path("outcome").asText("unavailable"));
                increment(stageCounts.get("step4"),response.path("status").asText("unavailable"));
                String run=storage.path("qualification").path("runId").asText();if(!run.isBlank())runIds.add(run);
                var profile=engine.get("customers/"+customer);reads++;JsonNode atEligibility=null;
                int version=response.path("qualification").path("profileVersion").asInt();
                for(var candidate:profile.path("profiles"))if(candidate.path("version").asInt()==version&&!Instant.parse(candidate.path("createdAt").asText()).isAfter(Instant.parse(e.path("eligibleAt").asText())))atEligibility=candidate.path("data");
                if(atEligibility!=null){
                    increment(stageCounts.get("step1"),"historical-profile-joined");
                    if(atEligibility.path("creditScore").isNumber()){int score=atEligibility.path("creditScore").asInt();segment(segments,"creditScore:"+(score<680?"below680":score<740?"680to739":"740plus"),hasSelection);}
                    if(atEligibility.path("bankingTenureMonths").isNumber())segment(segments,"tenure:"+(atEligibility.path("bankingTenureMonths").asInt()<12?"below12months":"12plusMonths"),hasSelection);
                    double income=atEligibility.path("monthlyIncomeUsd").asDouble();
                    if(income>0&&atEligibility.path("monthlyDebtPaymentsUsd").isNumber())segment(segments,"debtToIncome:"+(atEligibility.path("monthlyDebtPaymentsUsd").asDouble()/income<=0.35?"upTo35pct":"above35pct"),hasSelection);
                }else increment(failures,"historical-profile-unavailable");
            }catch(Exception ex){increment(failures,"steps1-4-unavailable");}
            try{var packages=engine.get("customers/"+customer+"/campaign-packages");reads++;for(var pack:packages.path("items"))increment(stageCounts.get("step7"),pack.path("status").asText("unavailable"));}catch(Exception ex){increment(failures,"execution-unavailable");}
        }
        for(String run:runIds.stream().limit(16).toList())try{
            var marketing=engine.get("marketing/runs/"+run);reads++;
            increment(stageCounts.get("step2"),"run:"+marketing.path("status").asText());
        }catch(Exception e){increment(failures,"marketing-unavailable");}
        var cells=new ArrayList<JsonNode>();segments.forEach((key,counts)->{
            if(counts[0]>=minimumSupport&&counts[1]>=minimumSupport&&counts[0]-counts[1]>=minimumSupport)
                cells.add(obj("metricId","segment:"+key,"segment",key,"eligible",counts[0],"selected",counts[1],"acceptancePct",100d*counts[1]/counts[0],"kind","ASSOCIATION"));
        });
        var stages=new ArrayList<JsonNode>();stageCounts.forEach((key,value)->stages.add(obj("stage",key,"counts",supported(value,minimumSupport),"smallCellsSuppressed",value.values().stream().anyMatch(v->v<minimumSupport))));
        return obj("snapshotId",id(),"generatedAt",Instant.now().toString(),"sourceMode",mode,"sampleSize",enrollments.size(),"sampleTruncated",truncated,
            "apiReads",reads,"stages",stages,"segments",cells,"sourceFailures",failures,
            "limitations",List.of("Stage 3/4/7 statuses are current snapshots; they do not reconstruct delivery at prediction time.",
                "Step 7 packages are exports, not proof of delivery. Step 8 selections are not exposure events.",
                "Segments describe sampled eligible customers and cannot establish causality or generalize to all applicants.",
                "No raw customer identifiers, contacts or bureau documents are sent to models."));
    }
    private static void segment(Map<String,int[]> groups,String key,boolean selected){var counts=groups.computeIfAbsent(key,k->new int[2]);counts[0]++;if(selected)counts[1]++;}
}

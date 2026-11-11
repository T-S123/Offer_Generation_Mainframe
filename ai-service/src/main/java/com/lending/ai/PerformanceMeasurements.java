package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;

/** Deterministic versioned cohort measurements with indexed selections and supported assessment-reason counts. */
public final class PerformanceMeasurements {
    private final RecordedEvidence evidence;private final int followUpDays;
    public PerformanceMeasurements(Store store,int days){evidence=new RecordedEvidence(store);followUpDays=days;}
    public JsonNode query(String publication,MeasurementScope scope){
        var facts=evidence.facts(publication,scope);var assessed=new TreeMap<String,JsonNode>();var eligible=new TreeMap<String,JsonNode>();var refs=new TreeSet<String>();
        var sources=new HashSet<String>();
        var selections=new HashMap<String,List<JsonNode>>();
        for(var f:facts){sources.add(f.path("sourceId").asText());String kind=f.path("kind").asText(),customer=f.path("customerKey").asText();
            if(kind.equals("SELECTION"))selections.computeIfAbsent(customer,key->new ArrayList<>()).add(f);
            if(kind.equals("ASSESSMENT")&&variant(scope,f.path("data")))first(assessed,customer,f);
            if(kind.equals("ENROLLMENT")&&(scope.variant().equals("OFFER_FAMILY")||scope.variant().equals("ORIGINAL")&&!f.at("/data/variant").asText().equals("PERSONALIZED")||scope.variant().equals("PERSONALIZED")&&variant(scope,f.path("data"))))first(eligible,customer,f);
        }
        boolean assessedBasis=scope.dateBasis().equals("FIRST_ASSESSED");
        var cohort=new TreeMap<String,JsonNode>();(assessedBasis?assessed:eligible).forEach((key,f)->{if(scope.includes(f.path("occurredAt").asText()))cohort.put(key,f);});
        int assessedCount=assessedBasis?cohort.size():0,eligibleCount=0,selected=0,mature=0,missingWindows=0,unresolvedAssessments=0;
        var reasons=new TreeMap<String,Integer>();
        var dates=new TreeMap<String,int[]>();var eligibleSubjects=new TreeSet<String>();
        for(var entry:cohort.entrySet()){
            String customer=entry.getKey();var origin=entry.getValue();refs.add(origin.path("evidenceId").asText());
            String day=Instant.parse(origin.path("occurredAt").asText()).atZone(ZoneId.of(scope.timezone())).toLocalDate().toString();int[] daily=dates.computeIfAbsent(day,k->new int[3]);if(assessedBasis)daily[0]++;
            if(assessedBasis){var codes=new HashSet<String>();for(var reason:origin.at("/data/reasons"))if(reason.asText().matches("[A-Z0-9_:-]{1,80}"))codes.add(reason.asText());codes.forEach(code->reasons.merge(code,1,Integer::sum));}
            if(assessedBasis&&!Set.of("ELIGIBLE","INELIGIBLE").contains(origin.at("/data/status").asText()))unresolvedAssessments++;
            boolean qualifies=!assessedBasis||origin.at("/data/status").asText().equals("ELIGIBLE");if(!qualifies)continue;
            eligibleCount++;daily[1]++;eligibleSubjects.add(customer);var enrollment=eligible.get(customer);
            if(enrollment==null||!enrollment.at("/data/followUpEndsAt").isTextual()){missingWindows++;continue;}
            refs.add(enrollment.path("evidenceId").asText());Instant start=Instant.parse(enrollment.path("occurredAt").asText()),end=Instant.parse(enrollment.at("/data/followUpEndsAt").asText());
            if(end.isBefore(start)){missingWindows++;continue;}
            Instant maximum=start.plus(Duration.ofDays(followUpDays));if(end.isAfter(maximum))end=maximum;
            if(!scope.cutoff().isBefore(end.plus(Duration.ofHours(24))))mature++;
            boolean found=false;for(var selection:selections.getOrDefault(customer,List.of()))if(selection.at("/data/familyId").equals(enrollment.at("/data/familyId"))){
                Instant at=Instant.parse(selection.path("occurredAt").asText());boolean termMatch=scope.variant().equals("OFFER_FAMILY")||scope.variant().equals(selection.at("/data/kind").asText());
                if(!scope.variant().equals("OFFER_FAMILY"))termMatch&=hash(selection.at("/data/terms")).equals(hash(enrollment.at("/data/terms")));
                if(termMatch&&!at.isBefore(start)&&!at.isAfter(end)){found=true;refs.add(selection.path("evidenceId").asText());}
            }
            if(found){selected++;daily[2]++;}
        }
        boolean complete=facts.stream().filter(f->f.path("kind").asText().equals("WATERMARK")).anyMatch(f->{var d=f.path("data");if(!d.path("complete").asBoolean()||!d.path("includesAllAdmissions").asBoolean()||d.path("followUpDays").asInt()!=followUpDays)return false;
            Instant first=scope.startDate()==null?cohort.values().stream().map(v->Instant.parse(v.path("occurredAt").asText())).min(Instant::compareTo).orElse(scope.cutoff()):scope.start();
            Instant last=scope.endDate()==null?scope.cutoff():scope.end().isAfter(scope.cutoff())?scope.cutoff():scope.end().minusNanos(1);
            return !Instant.parse(d.path("entryStartsAt").asText()).isAfter(first)&&!Instant.parse(d.path("entryEndsAt").asText()).isBefore(last)&&!Instant.parse(d.path("through").asText()).isBefore(scope.cutoff().minus(Duration.ofHours(24)));});
        // A native storage cursor covers eligible families only; it cannot certify all assessment admissions.
        var limitations=new ArrayList<String>();if(!complete)limitations.add("Complete assessment-admission and outcome coverage has not been certified for this scope.");
        if(assessedBasis&&assessed.isEmpty())limitations.add("First-assessment dates are not recorded in the available source. Eligible-at dates are not substituted.");
        if(!assessedBasis)limitations.add("Eligibility cannot use an eligible-only cohort as its assessed denominator.");
        if(unresolvedAssessments>0)limitations.add("Some assessment outcomes are unresolved; observed eligibility is unavailable rather than treating them as ineligible.");
        if(scope.endDate()!=null&&scope.end().isAfter(scope.cutoff()))limitations.add("The requested period extends beyond the evidence cutoff; this is a partial-period result.");
        if(scope.sourceMode().equals("DEMO"))limitations.add("Native events without a recorded ingestion time are excluded from cutoff-based measurements.");
        if(missingWindows>0)limitations.add("Some eligible customers have no recorded follow-up window; acceptance is unavailable.");
        if(sources.size()>1)limitations.add("Multiple source identities are present; records require reconciliation before a validated comparison.");
        boolean available=eligibleCount>0&&missingWindows==0&&sources.size()<=1;var daily=new ArrayList<JsonNode>();dates.forEach((day,c)->daily.add(obj("date",day,"assessed",assessedBasis?c[0]:null,"eligible",c[1],"selected",c[2])));
        return obj("metricId","measurement:"+hash(List.of(publication,scope,facts)),"publicationId",publication,"definition",scope.definition(followUpDays),"sourceMode",scope.sourceMode(),"scope",scope.variant(),"asOf",scope.evidenceCutoff(),"offerVersion",scope.offerVersion(),
            "assessedCount",assessedBasis&&(!cohort.isEmpty()||complete)?assessedCount:null,"E",eligibleCount,"Y",selected,"unresolvedAssessments",unresolvedAssessments,"eligibilityPct",assessedBasis&&assessedCount>0&&unresolvedAssessments==0&&sources.size()<=1?100d*eligibleCount/assessedCount:null,"acceptanceAmongEligiblePct",available?100d*selected/eligibleCount:null,
            "acceptanceInterval",available?OutcomeService.interval(selected,eligibleCount):null,"eligibleCohortHash",hash(eligibleSubjects),"firstEligibleAt",eligibleSubjects.stream().filter(eligible::containsKey).map(c->eligible.get(c).path("occurredAt").asText()).min(Comparator.comparing(Instant::parse)).orElse(null),
            "matureEnrollments",mature,"provisionalEnrollments",eligibleCount-mature,"maturity",eligibleCount==0?"UNAVAILABLE":mature==eligibleCount?"MATURE":"PROVISIONAL","coverage",complete&&sources.size()<=1?"OPERATIONAL_SOURCE_RECONCILED":"INCOMPLETE_DATA","followUpDays",followUpDays,"dailyCounts",daily,"assessmentReasons",reasons.entrySet().stream().filter(e->e.getValue()>=10).limit(25).map(e->obj("reason",e.getKey(),"count",e.getValue())).toList(),"evidenceIds",refs.stream().limit(200).toList(),"evidenceCount",refs.size(),"limitations",limitations);
    }
    private static boolean variant(MeasurementScope s,JsonNode data){return s.variant().equals(data.path("variant").asText("OFFER_FAMILY"));}
    private static void first(Map<String,JsonNode> map,String key,JsonNode fact){var old=map.get(key);if(old==null||Instant.parse(fact.path("occurredAt").asText()).isBefore(Instant.parse(old.path("occurredAt").asText())))map.put(key,fact);}
}

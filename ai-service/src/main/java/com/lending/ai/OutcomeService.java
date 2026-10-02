package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;

/** Append-only facts preserve historical denominators; each performance snapshot uses one captured clock reading. */
public final class OutcomeService {
    private final Store store;private final Http marketing;private final int followUpDays;private final Clock clock;
    public OutcomeService(Store store,Http marketing,int days){this(store,marketing,days,Clock.systemUTC());}
    OutcomeService(Store store,Http marketing,int days,Clock clock){require(days>=1&&days<=365,"FOLLOW_UP","Use 1-365 follow-up days");this.store=store;this.marketing=marketing;followUpDays=days;this.clock=Objects.requireNonNull(clock);}
    /** Replays ordered storage events before advancing the durable cursor; a repeated page is harmless. */
    public synchronized JsonNode synchronize(){
        var cursor=store.find("CURSOR","storage");long after=cursor.map(c->c.data().path("sequence").asLong()).orElse(0L);int pages=0,events=0;
        while(pages++<20){
            var page=marketing.call("GET","storage/events?after="+after+"&limit=100",null);var rows=page.path("items");
            require(rows.isArray(),"SOURCE_SHAPE","Invalid storage event page");
            for(var event:rows){ingestStorage(event);after=Math.max(after,event.path("sequence").asLong());events++;}
            var state=obj("sequence",after,"checkedAt",Instant.now().toString(),"sourceMode","DEMO","completePage",rows.size()<100);
            if(cursor.isEmpty())cursor=Optional.of(store.create("CURSOR","storage",state));else cursor=Optional.of(store.update("CURSOR","storage",cursor.get().version(),state));
            if(rows.size()<100)break;
        }
        return obj("events",events,"cursor",after,"checkedAt",Instant.now().toString());
    }
    public synchronized void ingestStorage(JsonNode event){
        String eventId=text(event,"eventId");var prior=store.find("OUTCOME_EVENT",eventId);
        if(prior.isPresent()){require(hash(prior.get().data()).equals(hash(event)),"SOURCE_CONFLICT","Event identity changed");return;}
        var offer=event.path("offer");String catalog=offer.path("qualification").path("catalogOfferId").asText();
        if(catalog.isBlank()){store.create("OUTCOME_EVENT",eventId,event);return;}
        String customer=text(offer,"customerId"),key=hash(List.of("DEMO",catalog,customer));
        var enrollment=store.find("ENROLLMENT",key);String status=offer.path("preScreen").path("status").asText();
        boolean eligible=offer.path("qualification").path("responseVersion").asLong()>0&&Set.of("AWAITING_CREATION","ACTIVE").contains(status);
        if(eligible&&(enrollment.isEmpty()||Instant.parse(event.path("occurredAt").asText()).isBefore(Instant.parse(enrollment.get().data().path("eligibleAt").asText())))){
            Instant at=Instant.parse(event.path("occurredAt").asText()),end=at.plus(Duration.ofDays(followUpDays));
            String expiry=offer.path("preScreen").path("validUntil").asText("");if(!expiry.isBlank())try{end=end.isBefore(Instant.parse(expiry))?end:Instant.parse(expiry);}catch(Exception ignored){}
            var n=obj("catalogOfferId",catalog,"customerKey",customer,"offerFamilyId",offer.path("id"),"eligibleAt",at.toString(),"followUpEndsAt",end.toString(),
                "sourceMode","DEMO","sourceEventId",eventId,"catalogOfferVersion",offer.path("qualification").path("catalogOfferVersion"),"terms",offer.path("preScreen").path("terms"));
            if(enrollment.isEmpty())store.create("ENROLLMENT",key,n);else store.update("ENROLLMENT",key,enrollment.get().version(),n);
        }
        var selection=offer.path("selection");if(selection.isObject()&&!selection.path("requestId").asText().isBlank()){
            String selectionKey=hash(List.of("DEMO",selection.path("requestId").asText()));
            var fact=obj("catalogOfferId",catalog,"customerKey",customer,"familyId",offer.path("id"),"selectedAt",selection.path("selectedAt"),"kind",selection.path("kind"),
                "terms",selection.path("terms"),"sourceMode","DEMO","requestId",selection.path("requestId"),"sourceEventId",eventId);
            if(store.find("SELECTION",selectionKey).isEmpty())store.create("SELECTION",selectionKey,fact);
        }
        store.create("OUTCOME_EVENT",eventId,event);
    }
    private List<JsonNode> all(String kind){var rows=new ArrayList<JsonNode>();String after="";for(;;){var page=store.list(kind,after,100);for(var r:page)rows.add(r.data());if(page.size()<100)break;after=page.get(page.size()-1).id();}return rows;}
    /** Captures current time once so a backward clock adjustment cannot reject the service's own snapshot cutoff. */
    public JsonNode performanceNow(String offerId,String mode){
        Instant capturedNow=clock.instant();return performance(offerId,capturedNow.toString(),mode,capturedNow);
    }
    /** Computes historical measures against one clock reading, still rejecting explicitly requested future cutoffs. */
    public JsonNode performance(String offerId,String asOf,String mode){
        return performance(offerId,asOf,mode,clock.instant());
    }
    /** Uses the captured reference time consistently for cutoff validation and source freshness. */
    private JsonNode performance(String offerId,String asOf,String mode,Instant capturedNow){
        require(mode.equals("DEMO")||mode.equals("OBSERVED_REAL"),"SOURCE_MODE","Explicit source mode required");
        Instant now=Instant.parse(asOf);require(!now.isAfter(capturedNow),"AS_OF_FUTURE","As-of cannot be in the future");var enrolled=all("ENROLLMENT").stream().filter(e->e.path("catalogOfferId").asText().equals(offerId)&&e.path("sourceMode").asText().equals(mode)&&!Instant.parse(e.path("eligibleAt").asText()).isAfter(now)).toList();
        var selections=all("SELECTION").stream().filter(s->s.path("catalogOfferId").asText().equals(offerId)&&s.path("sourceMode").asText().equals(mode)).toList();
        int selected=0,original=0,mature=0;
        for(var e:enrolled){
            Instant start=Instant.parse(e.path("eligibleAt").asText()),end=Instant.parse(e.path("followUpEndsAt").asText());
            if(!now.isBefore(end.plus(Duration.ofHours(24))))mature++;
            boolean family=false,exact=false;
            for(var s:selections)if(s.path("customerKey").asText().equals(e.path("customerKey").asText())){
                Instant at=Instant.parse(s.path("selectedAt").asText());
                if(!at.isBefore(start)&&!at.isAfter(end)&&!at.isAfter(now)){family=true;if(s.path("kind").asText().equals("ORIGINAL")&&hash(s.path("terms")).equals(hash(e.path("terms"))))exact=true;}
            }
            if(family)selected++;if(exact)original++;
        }
        var cursor=store.find("CURSOR","storage");boolean complete=mode.equals("DEMO")&&cursor.isPresent()&&cursor.get().data().path("completePage").asBoolean()
            &&Duration.between(Instant.parse(cursor.get().data().path("checkedAt").asText()),capturedNow).compareTo(Duration.ofHours(24))<0;
        var operational=store.find("SOURCE_WATERMARK",hash(List.of(mode,offerId)));
        Integer assessed=null;Double eligibility=null;String coverage=complete?"STORAGE_HISTORY_RECONCILED":"INCOMPLETE_DATA";
        if(operational.isPresent()){
            var coverageData=operational.get().data().path("data");Instant through=Instant.parse(coverageData.path("through").asText());
            boolean reconciled=coverageData.path("complete").asBoolean()&&!through.isBefore(now.minus(Duration.ofHours(24)))&&!through.isAfter(now);
            if(reconciled)coverage="OPERATIONAL_SOURCE_RECONCILED";
            if(reconciled&&coverageData.path("includesAllAdmissions").asBoolean()){
                Instant start=Instant.parse(coverageData.path("entryStartsAt").asText()),end=Instant.parse(coverageData.path("entryEndsAt").asText());
                Set<String> subjects=new HashSet<>();
                for(var fact:all("ASSESSMENT"))if(fact.path("catalogOfferId").asText().equals(offerId)&&fact.path("sourceMode").asText().equals(mode)){
                    Instant at=Instant.parse(fact.path("occurredAt").asText());if(!at.isBefore(start)&&!at.isAfter(end))subjects.add(fact.path("customerKey").asText());
                }
                boolean enrollmentCoverage=enrolled.stream().allMatch(e->subjects.contains(e.path("customerKey").asText())&&!Instant.parse(e.path("eligibleAt").asText()).isBefore(start)&&!Instant.parse(e.path("eligibleAt").asText()).isAfter(end));
                if(enrollmentCoverage){assessed=subjects.size();eligibility=assessed==0?null:100d*enrolled.size()/assessed;}else coverage="INCOMPLETE_DATA";
            }
        }
        long exposed=all("EXPOSURE").stream().filter(e->e.path("catalogOfferId").asText().equals(offerId)&&e.path("sourceMode").asText().equals(mode)&&e.path("data").path("stage").asText().equals("CLIENT_RENDERED")&&!Instant.parse(e.path("occurredAt").asText()).isAfter(now)).map(e->e.path("customerKey").asText()).distinct().count();
        int eligible=enrolled.size();return obj("metricId","performance:"+hash(List.of(offerId,asOf,mode)),"offerId",offerId,"asOf",asOf,"sourceMode",mode,"scope","OFFER_FAMILY","E",eligible,"Y",selected,
            "eligibleCohortHash",hash(enrolled.stream().map(e->e.path("customerKey").asText()).sorted().distinct().toList()),"firstEligibleAt",enrolled.stream().map(e->e.path("eligibleAt").asText()).min(String::compareTo).orElse(null),
            "acceptanceAmongEligiblePct",eligible==0?null:100d*selected/eligible,"acceptanceInterval",interval(selected,eligible),"originalTermsSelections",original,"originalTermsAcceptancePct",eligible==0?null:100d*original/eligible,
            "matureEnrollments",mature,"provisionalEnrollments",eligible-mature,"maturity",eligible==0?"UNAVAILABLE":eligible==mature?"MATURE":"PROVISIONAL",
            "assessedCount",assessed,"eligibilityPct",eligibility,"coverage",coverage,"clientRenderedSubjects",exposed,
            "operationalWatermark",operational.map(Store.Saved::data).orElse(null),
            "sourceWatermark",cursor.map(Store.Saved::data).orElse(null),"followUpDays",followUpDays,
            "limitations",List.of(assessed==null?"Complete assessment-admission coverage is unavailable; observed eligibility cannot be computed.":"Eligibility uses the operational source admission ledger.",mode.equals("DEMO")?"Native storage events are demo observations.":"Operational facts retain their configured source identity and correction history.","Exposure is diagnostic and does not replace the eligible denominator."));
    }
    /** Descriptive Wilson interval assumes independent subject selections; it is not causal or clustered uncertainty. */
    static JsonNode interval(int selected,int eligible){
        if(eligible==0)return obj("status","UNAVAILABLE","lowPct",null,"highPct",null);
        double p=(double)selected/eligible,z=1.959963984540054,den=1+z*z/eligible;
        double middle=(p+z*z/(2*eligible))/den,radius=z*Math.sqrt(p*(1-p)/eligible+z*z/(4d*eligible*eligible))/den;
        return obj("status","DESCRIPTIVE","method","WILSON_95","lowPct",100*Math.max(0,middle-radius),"highPct",100*Math.min(1,middle+radius),
            "assumption","Independent eligible-subject selections; campaign/time dependence is not modeled. This is not forecast or causal uncertainty.");
    }
    public JsonNode compare(JsonNode prediction,JsonNode observed){
        boolean sameMode=prediction.path("sourceMode").asText().equals(observed.path("sourceMode").asText());
        boolean sameTarget=prediction.path("scope").asText().equals(observed.path("scope").asText());
        boolean sameWindow=!prediction.has("followUpDays")||prediction.path("followUpDays").asInt()==observed.path("followUpDays").asInt();
        boolean sameCohort=!prediction.has("eligibleCohortHash")||prediction.path("eligibleCohortHash").equals(observed.path("eligibleCohortHash"));
        boolean prospective=!prediction.has("eligibleCohortHash")||!observed.hasNonNull("firstEligibleAt")||!Instant.parse(prediction.path("generatedAt").asText()).isAfter(Instant.parse(observed.path("firstEligibleAt").asText()));
        boolean calibrated=prediction.path("predictionKind").asText().equals("CALIBRATED_SELECTION");
        String status=!sameMode||!sameTarget||!sameWindow?"TARGET_MISMATCH":!sameCohort||!prospective?"COHORT_MISMATCH":!Set.of("STORAGE_HISTORY_RECONCILED","OPERATIONAL_SOURCE_RECONCILED").contains(observed.path("coverage").asText())?"INCOMPLETE_DATA":!observed.path("maturity").asText().equals("MATURE")?"PROVISIONAL":calibrated?"MATCH":"DIAGNOSTIC_ONLY";
        Double gap=status.equals("MATCH")&&prediction.path("estimatePct").isNumber()&&observed.path("acceptanceAmongEligiblePct").isNumber()?observed.path("acceptanceAmongEligiblePct").asDouble()-prediction.path("estimatePct").asDouble():null;
        return obj("metricId","comparison:"+hash(List.of(prediction,observed)),"prediction",prediction,"observed",observed,"comparability",status,"gapPercentagePoints",gap);
    }
}

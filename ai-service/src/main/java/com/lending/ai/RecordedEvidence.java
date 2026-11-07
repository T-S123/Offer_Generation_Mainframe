package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;

/** Replays append-only source revisions known at a cutoff instead of reading mutable current projections. */
public final class RecordedEvidence {
    private final Store store;
    public RecordedEvidence(Store store){this.store=store;}
    public List<JsonNode> all(String kind){var result=new ArrayList<JsonNode>();String after="";for(;;){var page=store.list(kind,after,100);for(var row:page)result.add(row.data());if(page.size()<100)return result;after=page.get(page.size()-1).id();}}
    public List<JsonNode> facts(String publication,MeasurementScope scope){
        var latest=new LinkedHashMap<String,JsonNode>();
        for(var record:all("SOURCE_EVENT")){
            var event=record.path("event");if(!record.path("sourceMode").asText().equals(scope.sourceMode())||!event.path("publicationId").asText().equals(publication))continue;
            if(!record.hasNonNull("ingestedAt")||Instant.parse(record.path("ingestedAt").asText()).isAfter(scope.cutoff())||Instant.parse(event.path("occurredAt").asText()).isAfter(scope.cutoff()))continue;
            String key=record.path("sourceId").asText()+":"+event.path("kind").asText()+":"+event.path("businessKey").asText();
            var old=latest.get(key);if(old==null||old.path("revision").asInt()<event.path("revision").asInt()){
                var d=event.path("data");
                latest.put(key,obj("kind",event.path("kind"),"customerKey",event.path("customerKey"),"occurredAt",event.path("occurredAt"),"data",d,"revision",event.path("revision"),"sourceId",record.path("sourceId"),"evidenceId","source:"+record.path("sourceId").asText()+":"+event.path("eventId").asText(),"recordedAt",record.path("ingestedAt")));
            }
        }
        var result=new ArrayList<>(latest.values().stream().filter(f->!f.path("data").hasNonNull("catalogOfferVersion")||f.at("/data/catalogOfferVersion").asInt()==scope.offerVersion()).toList());
        if(scope.sourceMode().equals("DEMO"))for(var event:storage(publication,scope)){
            var offer=event.path("offer");String eventId=event.path("eventId").asText();
            var pre=offer.path(scope.variant().equals("PERSONALIZED")?"marketing":"preScreen");String customer=offer.path("customerId").asText();
            if(offer.at("/qualification/responseVersion").asInt()>0&&Set.of("AWAITING_CREATION","ACTIVE").contains(pre.path("status").asText())&&(!scope.variant().equals("PERSONALIZED")||!pre.path("variantQualificationId").asText().isBlank())){
                result.add(obj("kind","ENROLLMENT","customerKey",customer,"occurredAt",event.path("occurredAt"),"data",obj("familyId",offer.path("id"),"followUpEndsAt",pre.path("validUntil"),"terms",pre.path("terms"),"variant",scope.variant(),"variantQualificationId",pre.path("variantQualificationId")),"evidenceId","storage:"+eventId,"sourceId","native-storage"));
            }
            if(offer.path("selection").hasNonNull("selectedAt")){
                var s=offer.path("selection");if(!Instant.parse(s.path("selectedAt").asText()).isAfter(scope.cutoff()))result.add(obj("kind","SELECTION","customerKey",customer,"occurredAt",s.path("selectedAt"),"data",obj("familyId",offer.path("id"),"kind",s.path("kind"),"terms",s.path("terms")),"evidenceId","storage:"+eventId,"sourceId","native-storage"));
            }
        }
        return result;
    }
    public List<JsonNode> storage(String publication,MeasurementScope scope){return all("OUTCOME_EVENT").stream().filter(e->e.at("/offer/qualification/catalogOfferId").asText().equals(publication)&&e.at("/offer/qualification/catalogOfferVersion").asInt()==scope.offerVersion()&&!Instant.parse(e.path("occurredAt").asText()).isAfter(scope.cutoff())&&store.find("OUTCOME_RECEIPT",e.path("eventId").asText()).map(r->!Instant.parse(r.data().path("recordedAt").asText()).isAfter(scope.cutoff())).orElse(false)).toList();}
}

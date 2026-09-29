package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;

/** Versioned operational-source boundary with append-only corrections, pseudonymous subjects and explicit coverage watermarks. */
public final class SourceFacts {
    private final Store store;private final String sourceId,mode;private final int followUpDays;
    public SourceFacts(Store store,String sourceId,String mode,int days){this.store=store;this.sourceId=sourceId;this.mode=mode;this.followUpDays=days;
        require(Set.of("DEMO","OBSERVED_REAL").contains(mode)&&sourceId.matches("[A-Za-z0-9._-]{1,80}"),"SOURCE_CONFIG","Configure an explicit operational source identity and mode");}
    public record Event(String schemaVersion,String eventId,String kind,String publicationId,String customerKey,String businessKey,int revision,String correctsEventId,String correctionReason,String occurredAt,JsonNode data) {}
    /** Commits idempotent projections before acknowledging each event; partial batches can safely be replayed. */
    public synchronized JsonNode ingest(JsonNode body){
        fields(body,"events");require(body.path("events").isArray()&&body.path("events").size()<=100,"EVENT_LIMIT","Supply up to 100 events");
        var receipts=new ArrayList<JsonNode>();for(var raw:body.path("events"))receipts.add(ingest(convert(raw,Event.class)));return obj("sourceId",sourceId,"sourceMode",mode,"receipts",receipts);
    }
    JsonNode ingest(Event event){
        require("1.0.0".equals(event.schemaVersion())&&event.eventId()!=null&&event.eventId().matches("[A-Za-z0-9._:-]{1,120}")&&event.businessKey()!=null&&event.businessKey().length()<=120&&event.revision()>=1,"EVENT_SCHEMA","Invalid event identity/version");
        require(event.data()!=null&&event.data().isObject(),"EVENT_SCHEMA","Structured event data required");store.get("PUBLICATION",event.publicationId());
        require(event.kind()!=null&&event.publicationId()!=null,"EVENT_SCHEMA","Fact kind and publication required");
        if(Set.of("ENROLLMENT","ASSESSMENT","FORECAST_FEATURE").contains(event.kind()))require(event.businessKey().equals(event.customerKey()),"BUSINESS_IDENTITY","Subject-level facts must use the subject key as business identity");
        if(event.kind().equals("WATERMARK"))require(event.businessKey().equals(event.publicationId()),"BUSINESS_IDENTITY","Watermarks use publication identity");
        Instant occurred=Instant.parse(event.occurredAt());require(!occurred.isAfter(Instant.now()),"EVENT_TIME","Future events are not outcomes");
        if(!event.kind().equals("WATERMARK"))require(event.customerKey()!=null&&event.customerKey().matches("[a-f0-9]{64}"),"SUBJECT_KEY","Operational subjects must use stable SHA-256 pseudonyms");
        String eventKey=hash(List.of(sourceId,event.eventId())),key=hash(List.of(sourceId,event.kind(),event.publicationId(),event.businessKey()));
        var oldEvent=store.find("SOURCE_EVENT",eventKey);if(oldEvent.isPresent()){require(oldEvent.get().data().path("fingerprint").asText().equals(hash(event)),"SOURCE_CONFLICT","Event ID changed");return obj("eventId",event.eventId(),"replayed",true);}
        var prior=store.find("SOURCE_CURRENT",key);
        if(prior.isPresent()&&prior.get().data().path("revision").asInt()==event.revision())require(prior.get().data().path("fingerprint").asText().equals(hash(event)),"SOURCE_CONFLICT","Source version changed");
        else if(prior.isPresent()){
            require(event.revision()==prior.get().data().path("revision").asInt()+1&&Objects.equals(event.correctsEventId(),prior.get().data().path("eventId").asText())&&event.correctionReason()!=null&&event.correctionReason().length()>=10,"CORRECTION_CHAIN","Corrections require the prior event identity, consecutive revision and reason");
            require(Objects.equals(event.customerKey(),prior.get().data().path("customerKey").asText(null)),"CORRECTION_IDENTITY","A correction cannot change the subject");
        }else require(event.revision()==1&&event.correctsEventId()==null,"CORRECTION_CHAIN","First fact must be revision one");
        var d=event.data();String projection=hash(List.of(mode,event.publicationId(),event.customerKey()==null?"":event.customerKey(),event.kind().equals("ENROLLMENT")?"":event.businessKey()));
        JsonNode fact;String kind;
        switch(event.kind()){
            case "ASSESSMENT"->{
                fields(d,"status","profileVersion","policyVersion","admissionId");require(Set.of("ELIGIBLE","INELIGIBLE","INCOMPLETE","FAILED").contains(text(d,"status")),"ASSESSMENT_STATUS","Explicit assessment status required");
                kind="ASSESSMENT";fact=obj("catalogOfferId",event.publicationId(),"customerKey",event.customerKey(),"sourceMode",mode,"occurredAt",event.occurredAt(),"data",d);
            }
            case "ENROLLMENT"->{
                fields(d,"familyId","followUpEndsAt","terms","sourceVersion");Instant end=Instant.parse(text(d,"followUpEndsAt"));
                require(!end.isBefore(occurred)&&!end.isAfter(occurred.plus(Duration.ofDays(followUpDays))),"FOLLOW_UP","Enrollment window differs from configured follow-up");
                require(d.path("terms").isObject()&&d.path("sourceVersion").isIntegralNumber()&&d.path("sourceVersion").asLong()>0,"ENROLLMENT_SCHEMA","Exact qualified terms and source version required");
                kind="ENROLLMENT";projection=hash(List.of(mode,event.publicationId(),event.customerKey()));
                fact=obj("catalogOfferId",event.publicationId(),"customerKey",event.customerKey(),"offerFamilyId",text(d,"familyId"),"eligibleAt",event.occurredAt(),"followUpEndsAt",end.toString(),"terms",d.path("terms"),"sourceMode",mode,"sourceEventId",event.eventId());
            }
            case "SELECTION"->{
                fields(d,"familyId","kind","terms","sourceVersion");require(Set.of("ORIGINAL","PERSONALIZED").contains(text(d,"kind"))&&d.path("terms").isObject()&&d.path("sourceVersion").asLong()>0,"SELECTION_SCHEMA","Confirmed exact-term selection required");
                kind="SELECTION";fact=obj("catalogOfferId",event.publicationId(),"customerKey",event.customerKey(),"familyId",text(d,"familyId"),"selectedAt",event.occurredAt(),"kind",d.path("kind"),"terms",d.path("terms"),"sourceMode",mode,"sourceEventId",event.eventId());
            }
            case "EXPOSURE"->{
                fields(d,"familyId","variant","terms","sourceVersion","stage");require(Set.of("CLIENT_RENDERED","SERVER_RENDERED","DELIVERED").contains(text(d,"stage")),"EXPOSURE_STAGE","Exposure stage must distinguish rendering and delivery");
                kind="EXPOSURE";fact=obj("catalogOfferId",event.publicationId(),"customerKey",event.customerKey(),"occurredAt",event.occurredAt(),"sourceMode",mode,"data",d);
            }
            case "FORECAST_FEATURE"->{
                fields(d,"baseProbability","featureAsOf","product","policyContext","followUpDays","scope");
                require(d.path("baseProbability").isNumber()&&d.path("baseProbability").asDouble()>=0&&d.path("baseProbability").asDouble()<=1&&text(d,"scope").equals("OFFER_FAMILY")&&d.path("followUpDays").asInt()==followUpDays,"FORECAST_SCHEMA","Comparable family forecast features required");
                require(!Instant.parse(text(d,"featureAsOf")).isAfter(occurred),"FEATURE_LEAKAGE","Forecast inputs must exist before the eligibility decision");
                require(Set.of("PERSONAL_LOAN","CREDIT_CARD","AUTO_LOAN").contains(text(d,"product"))&&!text(d,"policyContext").isBlank(),"FORECAST_SCHEMA","Product and stable choice-set policy context required");
                kind="FORECAST_FEATURE";fact=obj("catalogOfferId",event.publicationId(),"customerKey",event.customerKey(),"occurredAt",event.occurredAt(),"sourceMode",mode,"data",d);
            }
            case "WATERMARK"->{
                fields(d,"through","complete","includesAllAdmissions","entryStartsAt","entryEndsAt","followUpDays");
                require(d.path("complete").isBoolean()&&d.path("includesAllAdmissions").isBoolean()&&d.path("followUpDays").asInt()==followUpDays,"WATERMARK_SCHEMA","Explicit source coverage and matching follow-up required");
                Instant through=Instant.parse(text(d,"through")),start=Instant.parse(text(d,"entryStartsAt")),end=Instant.parse(text(d,"entryEndsAt"));
                require(!through.isAfter(occurred)&&!end.isBefore(start)&&!end.isAfter(through),"WATERMARK_TIME","Invalid reconciled source window");
                kind="SOURCE_WATERMARK";projection=hash(List.of(mode,event.publicationId()));
                fact=obj("catalogOfferId",event.publicationId(),"sourceMode",mode,"data",d,"checkedAt",Instant.now().toString(),"sourceId",sourceId);
            }
            default->throw new Fault(422,"EVENT_KIND","Unsupported operational fact");
        }
        String fingerprint=hash(event);var current=obj("eventId",event.eventId(),"revision",event.revision(),"customerKey",event.customerKey(),"fingerprint",fingerprint,"projectionKind",kind,"projectionId",projection);
        var projected=store.find(kind,projection);
        if(projected.isEmpty())store.create(kind,projection,fact);else store.update(kind,projection,projected.get().version(),fact);
        if(prior.isEmpty())store.create("SOURCE_CURRENT",key,current);else store.update("SOURCE_CURRENT",key,prior.get().version(),current);
        store.create("SOURCE_EVENT",eventKey,obj("sourceId",sourceId,"sourceMode",mode,"fingerprint",fingerprint,"event",event,"ingestedAt",Instant.now().toString()));
        return obj("eventId",event.eventId(),"revision",event.revision(),"replayed",false);
    }
}

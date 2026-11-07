package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;

/** Traces recorded exact-offer eligibility; current profiles and product-level underwriting never substitute for missing history. */
public final class CustomerEligibilityTrace {
    private final RecordedEvidence evidence;private final EngineGateway engine;private final int days;
    public CustomerEligibilityTrace(Store store,EngineGateway engine,int days){evidence=new RecordedEvidence(store);this.engine=engine;this.days=days;}
    public JsonNode trace(String publication,String reference,MeasurementScope scope){
        require(reference!=null&&!reference.isBlank()&&reference.length()<=120,"CUSTOMER_REFERENCE","A bounded customer ID or reference is required");
        String customer=reference;var limitations=new ArrayList<String>();JsonNode profile=null;
        if(scope.sourceMode().equals("OBSERVED_REAL")){
            if(!reference.matches("[a-f0-9]{64}"))return absent(publication,reference,scope,"No authorized operational identity mapping is recorded. Supply the source's pseudonymous customer key.");
            var matches=evidence.facts(publication,scope).stream().filter(f->f.path("kind").asText().equals("ASSESSMENT")&&f.path("customerKey").asText().equals(reference)&&scope.includes(f.path("occurredAt").asText())&&f.at("/data/variant").asText("OFFER_FAMILY").equals(scope.variant())).sorted(Comparator.comparing(f->Instant.parse(f.path("occurredAt").asText()))).toList();
            if(matches.isEmpty())return absent(publication,reference,scope,"No assessment for this exact offer variant was recorded by the evidence cutoff.");
            var fact=matches.get(matches.size()-1);var d=fact.path("data");return obj("metricId","eligibility:"+hash(List.of(publication,reference,scope,fact)),"status","RECORDED","eligibility",d.path("status"),"customerReference",reference,"customerId",reference,"publicationId",publication,"offerVersion",scope.offerVersion(),"variant",scope.variant(),"assessedAt",fact.path("occurredAt"),"profileVersion",d.path("profileVersion"),"policyVersion",d.path("policyVersion"),"qualificationId",d.path("variantQualificationId"),"reasons",d.path("reasons"),"definition",scope.definition(days),"evidenceIds",List.of(fact.path("evidenceId").asText()),"limitations",List.of("This is the source's recorded decision; no new credit decision was made."));
        }
        try{require(engine!=null,"CUSTOMER_OWNER_UNAVAILABLE","Customer history owner is unavailable");var resolved=engine.get("customers/resolve?reference="+encode(reference));customer=resolved.path("customerId").asText();profile=engine.get("customers/"+encode(customer));}
        catch(Fault e){if(e.status==409)return obj("status","AMBIGUOUS","customerReference",reference,"publicationId",publication,"offerVersion",scope.offerVersion(),"variant",scope.variant(),"eligibility","NOT_RECORDED","limitations",List.of(e.getMessage()));if(e.status==404)return absent(publication,reference,scope,"The customer owner has no matching ID or external reference.");limitations.add("Customer history owner is unavailable; profile version could not be verified.");}
        final String subject=customer;
        var snapshots=evidence.storage(publication,scope).stream().filter(e->e.at("/offer/customerId").asText().equals(subject)&&(scope.dateBasis().equals("FIRST_ASSESSED")||scope.includes(e.path("occurredAt").asText()))).sorted(Comparator.comparing(e->Instant.parse(e.path("occurredAt").asText()))).toList();
        JsonNode chosen=null,historicalResponse=null;
        for(var event:snapshots){
            var candidate=event.path("offer");var item=candidate.path(scope.variant().equals("PERSONALIZED")?"marketing":"preScreen");
            if(scope.variant().equals("PERSONALIZED")&&item.path("variantQualificationId").asText().isBlank())continue;
            JsonNode response=historical(candidate,customer,publication,scope);
            if(scope.startDate()!=null&&scope.dateBasis().equals("FIRST_ASSESSED")){
                // A storage refresh is not a new assessment; personalized assessment dates are not present in this journal.
                if(scope.variant().equals("PERSONALIZED")||response==null||!response.hasNonNull("evaluatedAt")||!scope.includes(response.path("evaluatedAt").asText()))continue;
            }
            chosen=event;historicalResponse=response;
        }
        if(chosen==null)return absent(publication,reference,scope,"No exact "+scope.variant()+" qualification was recorded. General product underwriting does not prove eligibility for this offer; a bounded assessment-date query also requires the exact variant assessment timestamp.");
        var offer=chosen.path("offer");var item=offer.path(scope.variant().equals("PERSONALIZED")?"marketing":"preScreen");var q=offer.path("qualification");
        String status=item.path("status").asText();boolean eligible=Set.of("ACTIVE","AWAITING_CREATION").contains(status)&&!item.path("terms").isMissingNode();
        if(historicalResponse==null)limitations.add("The linked historical decision-response evidence is unavailable.");
        if(scope.variant().equals("PERSONALIZED"))limitations.add("The personalized qualification identity is recorded, but its own assessment timestamp is not in the storage journal.");
        Integer profileVersion=historicalResponse==null||!historicalResponse.at("/qualification/profileVersion").isIntegralNumber()?null:historicalResponse.at("/qualification/profileVersion").asInt();
        boolean profileRecorded=false;if(profile!=null&&profileVersion!=null)for(var p:profile.path("profiles"))if(p.path("version").asInt()==profileVersion)profileRecorded=true;
        if(!profileRecorded)limitations.add("The linked historical profile version is not available; the current profile was not substituted.");
        if(!eligible)limitations.add("The stored lifecycle status does not by itself prove a declined credit decision.");
        return obj("metricId","eligibility:"+hash(List.of(publication,customer,scope,chosen)),"status","RECORDED","eligibility",eligible?"ELIGIBLE":"NOT_RECORDED","recordedLifecycleStatus",status,"customerReference",reference,"customerId",customer,"publicationId",publication,"offerVersion",scope.offerVersion(),"variant",scope.variant(),"recordedAt",chosen.path("occurredAt"),"assessedAt",scope.variant().equals("PERSONALIZED")||historicalResponse==null?null:historicalResponse.path("evaluatedAt"),"definition",scope.definition(days),"profileVersion",profileVersion,"profileHistoryVerified",profileRecorded,"policyVersion",q.path("eligibilityPolicyVersion"),"qualificationId",scope.variant().equals("PERSONALIZED")?item.path("variantQualificationId"):q.path("qualificationId"),"bureauDecisionId",q.path("bureauDecisionId"),"underwritingDecisionId",q.path("underwritingDecisionId"),"terms",item.path("terms"),"validUntil",item.path("validUntil"),"evidenceIds",List.of("storage:"+chosen.path("eventId").asText()),"evidenceLinks",List.of(obj("label","Customer profile history","ownerPath","/api/v1/customers/"+encode(customer)),obj("label","Qualification history","ownerPath","/api/v1/decision-responses/"+encode(offer.path("id").asText())+"/history")),"limitations",limitations);
    }
    private JsonNode historical(JsonNode offer,String customer,String publication,MeasurementScope scope){
        if(engine==null)return null;long after=0;
        try{for(int page=0;page<10;page++){var rows=engine.get("decision-responses/"+encode(offer.path("id").asText())+"/history?after="+after+"&limit=100");
            for(var row:rows){var r=row.path("response");after=Math.max(after,r.path("version").asLong());if(r.path("version").asLong()==offer.at("/qualification/responseVersion").asLong()&&r.path("customerId").asText().equals(customer)&&r.at("/qualification/offerId").asText().equals(publication)&&r.at("/qualification/offerVersion").asInt()==scope.offerVersion())return r;}if(rows.size()<100)break;}
        }catch(Fault unavailable){return null;}return null;
    }
    private static JsonNode absent(String publication,String customer,MeasurementScope scope,String reason){return obj("metricId","eligibility:"+hash(List.of(publication,customer,scope,reason)),"status","NOT_RECORDED","eligibility","NOT_RECORDED","customerReference",customer,"publicationId",publication,"offerVersion",scope.offerVersion(),"variant",scope.variant(),"evidenceIds",List.of(),"limitations",List.of(reason));}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}

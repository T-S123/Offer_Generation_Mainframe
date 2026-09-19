/**
 * Minimized operational/enterprise vocabulary; selections remain bound to their original qualification
 * version and exact financial terms.
 */
package com.lending.offers.domain;
import static com.lending.offers.domain.Model.*;
import java.util.Map;

/**
 * Minimized operational/enterprise vocabulary; selections remain bound to their original qualification
 * version and exact financial terms.
 */
public final class StoredOffers {
    /** Prevents instantiation of this utility-only type. */
    private StoredOffers(){}
    public static final String TOPIC="offers.storage.updated.v1";
    /** Carries evidence data for stored offers operations. */
    public record Evidence(String runId,String riskId,String qualificationId,long responseVersion,String campaignId,int campaignVersion,String catalogOfferId,int catalogOfferVersion,String bureauDecisionId,String underwritingDecisionId,String eligibilityPolicyVersion) {}
    /** Carries item data for stored offers operations. */
    public record Item(String category,String status,boolean visible,Terms terms,Metrics metrics,String variantQualificationId,String validUntil) {}
    /** Carries choice data for stored offers operations. */
    public record Choice(String requestId,long sourceVersion,String kind,Terms terms,String selectedAt,boolean available) {}
    /** Carries snapshot data for stored offers operations. */
    public record Snapshot(String id,long version,String customerId,String product,String currency,Evidence qualification,Item preScreen,Item marketing,Choice selection,String modelId,String policyVersion,Integer cohort,Map<String,Double> modelMetrics,Map<String,Double> deltas) {
        /** Copies a stored offer snapshot with a new repository version. */
        public Snapshot version(long v){return new Snapshot(id,v,customerId,product,currency,qualification,preScreen,marketing,selection,modelId,policyVersion,cohort,modelMetrics,deltas);}
    }
    /** Carries event data for stored offers operations. */
    public record Event(String eventId,int schemaVersion,String type,String occurredAt,long sequence,Snapshot offer) {}
    /** Derives the repository lifecycle state from the current offer family and its qualification. */
    public static Item state(Item item,String status,boolean visible){return item==null?null:new Item(item.category(),status,visible,item.terms(),item.metrics(),item.variantQualificationId(),item.validUntil());}
    /** Derives original and personalized choice availability from current offer creation state. */
    public static Choice availability(Choice choice,long version,Item original,Item personal){if(choice==null)return null;Item target=choice.kind().equals("ORIGINAL")?original:personal;return new Choice(choice.requestId(),choice.sourceVersion(),choice.kind(),choice.terms(),choice.selectedAt(),choice.sourceVersion()==version&&target!=null&&target.visible()&&target.terms()!=null&&target.terms().equals(choice.terms()));}
}

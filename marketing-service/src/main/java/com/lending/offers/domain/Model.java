/**
 * Offer-domain records retain qualification evidence and bind customer confirmation to explicitly reviewed
 * financial terms.
 */
package com.lending.offers.domain;
import com.lending.offers.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

/**
 * Offer-domain records retain qualification evidence and bind customer confirmation to explicitly reviewed
 * financial terms.
 */
public final class Model {
    /** Prevents instantiation of this utility-only type. */
    private Model(){}
    /** Carries terms data for model operations. */
    public record Terms(BigDecimal amountUsd,BigDecimal aprPct,BigDecimal annualFeeUsd,int termMonths) {
        /**
         * Creates a terms value from the supplied fields, filling omitted optional fields with their
         * compatibility defaults.
         */
        public Terms {amountUsd=amountUsd.setScale(2);aprPct=aprPct.setScale(2);annualFeeUsd=annualFeeUsd.setScale(2);}
    }
    /** Carries metrics data for model operations. */
    public record Metrics(double fitScore,double simulatedAcceptance,double monthlyPaymentUsd,double totalCostUsd,double affordability,double amountMatch,double costFit,double termFit) {}
    /** Carries candidate data for model operations. */
    public record Candidate(Terms terms,Metrics metrics) {}
    /** Carries alternative data for model operations. */
    public record Alternative(String kind,Terms terms,Metrics metrics,String variantQualificationId,String validUntil) {}
    /** Carries offer set data for model operations. */
    public record OfferSet(String id,long sourceVersion,String customerId,String riskId,String campaignId,String catalogOfferId,int catalogOfferVersion,String product,String currency,String status,String personalizationStatus,String createdAt,String validUntil,String modelId,String policyVersion,int cohort,Alternative original,Alternative personalized,Map<String,Double> deltas,List<String> explanations) {}
    /** Carries training data for model operations. */
    public record Training(String batchId,long seed) {}
    /** Carries selection data for model operations. */
    public record Selection(String requestId,long sourceVersion,String kind) {}
    /** Carries presented selection data for model operations. */
    public record PresentedSelection(String requestId,long sourceVersion,String kind,Terms terms) {}
    /** Carries page data for model operations. */
    public record Page<T>(List<T> items,String nextAfter) {}
    /** Carries cohort model data for model operations. */
    public record CohortModel(String id,String batchId,long seed,String algorithm,List<String> features,double[] means,double[] scales,double[][] centroids,double[][] preferences,Map<String,Double> metrics,String datasetHash,String trainedAt) {}
    /** Deserializes a JSON financial-terms object into the typed offer terms record. */
    public static Terms terms(JsonNode n){return Json.MAPPER.convertValue(n,Terms.class);}
}

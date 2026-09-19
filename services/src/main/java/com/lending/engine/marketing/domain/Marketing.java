/**
 * Marketing vocabulary includes immutable offer-scoped Business rule versions alongside catalog terms and
 * reservations.
 */
package com.lending.engine.marketing.domain;

import com.lending.engine.domain.Model.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * Marketing vocabulary includes immutable offer-scoped Business rule versions alongside catalog terms and
 * reservations.
 */
public final class Marketing {
    /** Prevents instantiation of this utility-only type. */
    private Marketing() {}
    public static final String RULE_VERSION="MKT-2026-001";
    /** Carries offer input data for marketing operations. */
    public record OfferInput(String name, Product product, Boolean active, String startsOn, String endsOn,
                             BigDecimal minimumAmountUsd, BigDecimal maximumAmountUsd,
                             BigDecimal illustrativeAprPct, BigDecimal annualFeeUsd, Integer termMonths) {}
    /** Carries offer data for marketing operations. */
    public record Offer(String id,int version,String createdAt,OfferInput data,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.lending.engine.simulation.domain.BusinessRules.RuleSet rules) {
        /**
         * Creates a offer value from the supplied fields, filling omitted optional fields with their
         * compatibility defaults.
         */
        public Offer(String id,int version,String createdAt,OfferInput data){this(id,version,createdAt,data,null);}
    }
    /** Carries offer update data for marketing operations. */
    public record OfferUpdate(Integer expectedVersion,OfferInput offer) {}
    /** Carries campaign input data for marketing operations. */
    public record CampaignInput(String name,Boolean active,String startsOn,String endsOn,Integer priority,
                                Integer capacity,Integer minimumTenureMonths,Boolean excludeExistingProduct,List<String> offerIds) {}
    /** Carries campaign data for marketing operations. */
    public record Campaign(String id,int version,String createdAt,CampaignInput data) {}
    /** Carries campaign update data for marketing operations. */
    public record CampaignUpdate(Integer expectedVersion,CampaignInput campaign) {}
    /** Carries policy input data for marketing operations. */
    public record PolicyInput(Integer cooldownDays,Integer rollingWindowDays,Integer maximumReservations,Integer reservationDays) {}
    /** Carries policy data for marketing operations. */
    public record Policy(int version,String ruleVersion,String createdAt,PolicyInput data) {}
    /** Carries policy update data for marketing operations. */
    public record PolicyUpdate(Integer expectedVersion,PolicyInput policy) {}
    /** Carries suppression input data for marketing operations. */
    public record SuppressionInput(Boolean active,String reason,String expiresOn) {}
    /** Carries suppression data for marketing operations. */
    public record Suppression(String customerId,int version,String updatedAt,SuppressionInput data) {}
    /** Carries suppression update data for marketing operations. */
    public record SuppressionUpdate(Integer expectedVersion,SuppressionInput suppression) {}
    /** Carries run request data for marketing operations. */
    public record RunRequest(String requestId,String populationId,List<String> campaignIds) {}
    /** Carries qualification data for marketing operations. */
    public record Qualification(String id,String riskId,String customerId,int profileVersion,
        Integer sourceProfileVersion,String sourceDecisionId,String currentDecisionId,Product product,
        String campaignId,int campaignVersion,String offerId,int offerVersion,
        String outcome,List<String> reasons,Integer suppressionVersion,String suppressionReason,String underwritingPolicyVersion,
        String ruleVersion,int policyVersion) {}
    /** Carries run data for marketing operations. */
    public record Run(String id,RunRequest request,String status,String createdAt,String finalizedAt,String cancelledAt,
        Policy policy,List<Campaign> campaigns,List<Offer> offers,int customerCount,int qualifiedCustomers,
        int qualifiedOffers,int reservationCount,Map<String,Long> reasonCounts) {}
    /** Carries reservation data for marketing operations. */
    public record Reservation(String id,String runId,String riskId,String customerId,String campaignId,
        String createdAt,String expiresAt,String releasedAt) {}
    /** Carries rule input data for marketing operations. */
    public record RuleInput(int customerOrdinal,int campaignSlot,boolean memberPresent,boolean sourceCurrent,
        boolean underwritingEligible,boolean underwritingCurrent,Boolean marketingOptIn,Boolean prescreenOptOut,
        boolean suppressed,boolean campaignActive,boolean campaignInDate,boolean offerActive,boolean offerInDate,
        Integer tenureMonths,int minimumTenureMonths,Boolean productHeld,boolean excludeExistingProduct,
        BigDecimal amount,BigDecimal minimumAmount,BigDecimal maximumAmount,Long secondsSinceReservation,
        int windowReservations,int campaignReservations,int campaignCapacity,int cooldownDays,int maximumReservations,boolean alreadyReserved) {}
    /** Carries rule result data for marketing operations. */
    public record RuleResult(boolean qualified,List<String> reasons,String ruleVersion) {}
    /** Carries run summary data for marketing operations. */
    public record RunSummary(String id,String populationId,String status,String createdAt,int qualifiedCustomers,int qualifiedOffers,int reservationCount) {}
}

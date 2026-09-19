/**
 * Step 3 vocabulary preserves independent observations and optional immutable offer-scoped policy inputs;
 * legacy JSON remains unchanged.
 */
package com.lending.engine.bureau.domain;

import com.lending.engine.domain.Model.*;
import com.lending.engine.marketing.domain.Marketing.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * Step 3 vocabulary preserves independent observations and optional immutable offer-scoped policy inputs;
 * legacy JSON remains unchanged.
 */
public final class Bureau {
    /** Prevents instantiation of this utility-only type. */
    private Bureau() {}
    public static final String POLICY="CBR-2026-001";
    /** Carries reference data for bureau operations. */
    public record Reference(String runId,String riskId,String qualificationId) {}
    /** Carries submit data for bureau operations. */
    public record Submit(String requestId,Reference source) {}
    /** Carries batch request data for bureau operations. */
    public record BatchRequest(String requestId,List<String> runIds) {}
    /** Carries source row data for bureau operations. */
    public record SourceRow(int ordinal,Qualification qualification) {}
    /** Carries snapshot data for bureau operations. */
    public record Snapshot(Reference reference,Qualification qualification,Profile customer,Offer offer) {}
    /** Defines the supported file status values for this domain. */
    public enum FileStatus { MATCHED, NO_HIT, FROZEN, AMBIGUOUS }
    /** Carries facts data for bureau operations. */
    public record Facts(FileStatus fileStatus,Integer creditScore,BigDecimal monthlyDebtUsd,
        Integer utilizationPct,Integer delinquencies12m,Integer inquiries6m,Integer oldestAccountMonths,
        Boolean bankruptcy,String reportedAt) {}
    /** Carries profile change data for bureau operations. */
    public record ProfileChange(Integer expectedVersion,String source,Facts facts) {}
    /** Carries bureau profile data for bureau operations. */
    public record BureauProfile(String subjectId,String customerId,int version,String source,String recordedAt,Facts facts) {}
    /** Carries credit input data for bureau operations. */
    public record CreditInput(String requestId,String subjectId,String customerId,Product product,
        BigDecimal amountUsd,BigDecimal monthlyIncomeUsd,String incomeSource,BigDecimal vehicleValueUsd,
        Integer termMonths,BigDecimal aprPct,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.lending.engine.simulation.domain.BusinessRules.RuleSet offerRules) {
        /**
         * Creates a credit input value from the supplied fields, filling omitted optional fields with
         * their compatibility defaults.
         */
        public CreditInput(String requestId,String subjectId,String customerId,Product product,BigDecimal amountUsd,BigDecimal monthlyIncomeUsd,String incomeSource,BigDecimal vehicleValueUsd,Integer termMonths,BigDecimal aprPct){this(requestId,subjectId,customerId,product,amountUsd,monthlyIncomeUsd,incomeSource,vehicleValueUsd,termMonths,aprPct,null);}
    }
    /** Carries assessment data for bureau operations. */
    public record Assessment(String id,String requestId,String subjectId,int bureauProfileVersion,String policyVersion,
        String outcome,List<String> reasons,String assessedAt,String validUntil,CreditInput input,BureauProfile bureauProfile) {}
    /** Carries result data for bureau operations. */
    public record Result(String requestId,String riskId,Reference source,String status,Assessment decision,String reason,String completedAt) {}
    /** Carries work data for bureau operations. */
    public record Work(String id,String fingerprint,String runId,String riskId,String qualificationId,
        String status,int attempts,String leaseToken,String result) {}
    /** Validates a bureau identifier against the supported character and length rules. */
    public static void id(String value,String field) {
        if(value==null||!value.matches("[A-Za-z0-9_.:-]{1,80}"))throw new Problem(422,"Invalid "+field);
    }
    /** Validates the run, Risk ID and qualification identifiers in a Step 2 source reference. */
    public static void reference(Reference ref) {
        if(ref==null)throw new Problem(422,"A finalized marketing qualification is required");
        id(ref.runId(),"runId");id(ref.riskId(),"riskId");id(ref.qualificationId(),"qualificationId");
    }
}

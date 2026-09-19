/**
 * Customer vocabulary with explicit preferences, synthetic contact channels and assessment-time decision
 * selection.
 */
package com.lending.engine.domain;

import java.math.BigDecimal;
import java.util.*;

/**
 * Customer vocabulary with explicit preferences, synthetic contact channels and assessment-time decision
 * selection.
 */
public final class Model {
    /** Prevents instantiation of this utility-only type. */
    private Model() {}
    /** Defines the supported product values for this domain. */
    public enum Product { PERSONAL_LOAN, CREDIT_CARD, AUTO_LOAN }
    /** Defines the supported status values for this domain. */
    public enum Status { ELIGIBLE, INELIGIBLE, INCOMPLETE }
    /** Carries customer input data for model operations. */
    public record CustomerInput(String externalReference, String displayName,
        BigDecimal monthlyIncomeUsd, BigDecimal monthlyDebtPaymentsUsd, Integer creditScore,
        Integer delinquencies12m, BigDecimal creditUtilizationPct,
        BigDecimal personalLoanAmountUsd, BigDecimal requestedCardLimitUsd,
        BigDecimal autoLoanAmountUsd, BigDecimal vehicleValueUsd, Integer vehicleAgeYears,
        Integer bankingTenureMonths, BigDecimal depositBalanceUsd, BigDecimal monthlySpendUsd,
        List<String> existingProducts, Boolean marketingOptIn, Boolean prescreenOptOut,List<String> preferredChannels) {
        /**
         * Creates a customer input value from the supplied fields, filling omitted optional fields with
         * their compatibility defaults.
         */
        public CustomerInput(String externalReference,String displayName,BigDecimal monthlyIncomeUsd,BigDecimal monthlyDebtPaymentsUsd,Integer creditScore,Integer delinquencies12m,BigDecimal creditUtilizationPct,BigDecimal personalLoanAmountUsd,BigDecimal requestedCardLimitUsd,BigDecimal autoLoanAmountUsd,BigDecimal vehicleValueUsd,Integer vehicleAgeYears,Integer bankingTenureMonths,BigDecimal depositBalanceUsd,BigDecimal monthlySpendUsd,List<String> existingProducts,Boolean marketingOptIn,Boolean prescreenOptOut){this(externalReference,displayName,monthlyIncomeUsd,monthlyDebtPaymentsUsd,creditScore,delinquencies12m,creditUtilizationPct,personalLoanAmountUsd,requestedCardLimitUsd,autoLoanAmountUsd,vehicleValueUsd,vehicleAgeYears,bankingTenureMonths,depositBalanceUsd,monthlySpendUsd,existingProducts,marketingOptIn,prescreenOptOut,null);}
    }
    /** Carries contact data for model operations. */
    public record Contact(String customerId,int version,String source,String email,String phone,String postalAddress,List<String> preferredChannels) {}
    /** Carries pipeline data for model operations. */
    public record Pipeline(String customerId,String token,String state,String runId,String updatedAt,String detail) {}
    /** Carries profile data for model operations. */
    public record Profile(String customerId, int version, String origin, String batchId,
                          String createdAt, String creditInformationSource, CustomerInput data) {}
    /** Carries decision data for model operations. */
    public record Decision(String id, String customerId, int profileVersion, Product product,
        Status status, List<String> reasons, String source, String sourceSystem,
        String sourceDecisionId, String policyVersion, String assessedAt, String validUntil) {}
    /** Carries customer data for model operations. */
    public record Customer(String id, List<Profile> profiles, List<Decision> decisions) {
        /** Returns the most recent customer profile revision. */
        public Profile current() { return profiles.get(profiles.size() - 1); }
        /** Selects the newest assessment for the current profile and requested product by assessment time. */
        public Decision latest(Product product) {
            Decision latest = null;
            for (Decision d : decisions) {
                if (d.profileVersion() == current().version() && d.product() == product &&
                    (latest == null || !java.time.Instant.parse(d.assessedAt()).isBefore(java.time.Instant.parse(latest.assessedAt())))) latest = d;
            }
            return latest;
        }
    }
    /** Carries update data for model operations. */
    public record Update(int expectedVersion, CustomerInput customer) {}
    /** Carries import decision data for model operations. */
    public record ImportDecision(String customerId, int profileVersion, Product product,
        Status status, List<String> reasons, String sourceSystem, String sourceDecisionId,
        String policyVersion, String assessedAt, String validUntil) {}
    /** Carries simulation request data for model operations. */
    public record SimulationRequest(Integer count, Long seed) {}
    /** Carries job data for model operations. */
    public record Job(String id, String status, int count, long seed, String createdAt,
                      String completedAt, Map<String, Long> outcomes, String error) {}
    /** Carries population request data for model operations. */
    public record PopulationRequest(List<Product> products, String batchId) {}
    /** Carries member data for model operations. */
    public record Member(String customerId, int profileVersion, Product product, String decisionId) {}
    /** Carries population data for model operations. */
    public record Population(String id, String createdAt, String asOfDate, String batchId,
        List<Product> products, int customerCount, int eligibleCustomers, int qualifiedPairs,
        Map<String, Long> exclusions, Set<String> policyVersions) {}
    /** Carries page data for model operations. */
    public record Page<T>(List<T> items, long total, int offset, int limit) {}
    /** Carries customer summary data for model operations. */
    public record CustomerSummary(String id, String displayName, int version, String origin,
        String batchId, Map<Product, Status> decisions, Boolean prescreenOptOut) {}
    /** Carries rule result data for model operations. */
    public record RuleResult(Product product, Status status, List<String> reasons, String policyVersion) {}
    /** Groups problem behavior for model operations. */
    public static final class Problem extends RuntimeException {
        public final int status;
        /** Creates an application failure carrying its HTTP status and diagnostic message. */
        public Problem(int status, String message) { super(message); this.status = status; }
    }
}

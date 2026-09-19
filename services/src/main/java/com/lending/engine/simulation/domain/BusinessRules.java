/**
 * Versioned offer-scoped rule forms. Consent, opt-out, suppression and matched bureau identity cannot be
 * disabled.
 */
package com.lending.engine.simulation.domain;
import com.lending.engine.domain.Model.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * Versioned offer-scoped rule forms. Consent, opt-out, suppression and matched bureau identity cannot be
 * disabled.
 */
public final class BusinessRules {
    /** Prevents instantiation of this utility-only type. */
    private BusinessRules(){}
    /** Carries stage data for business rules operations. */
    public record Stage(int minimumScore,BigDecimal minimumIncomeUsd,int maximumDtiPct,int maximumUtilizationPct,int maximumDelinquencies,
        BigDecimal maximumAmountUsd,int incomeMultiple,int maximumLtvPct,int maximumVehicleAge,int minimumTenureMonths,boolean excludeExistingProduct,
        int maximumInquiries,int minimumHistoryMonths,int maximumReportAgeDays,boolean allowBankruptcy) {}
    /** Carries rule set data for business rules operations. */
    public record RuleSet(String id,int version,String createdAt,Stage underwriting,Stage marketing,Stage bureau) {}
    /** Carries result data for business rules operations. */
    public record Result(String status,List<String> reasons) {
        /** Checks whether a rule-stage result reports eligibility. */
        public boolean eligible(){return status.equals("ELIGIBLE");}}
    /** Builds the initial versioned rule form from the demonstrated product policies. */
    public static RuleSet defaults(Product product){int i=product.ordinal();return new RuleSet("DEFAULT-"+product,1,"2026-01-01T00:00:00Z",
        new Stage(new int[]{660,640,620}[i],BigDecimal.valueOf(new int[]{2000,1500,1800}[i]),new int[]{40,45,45}[i],product==Product.CREDIT_CARD?80:100,1,BigDecimal.valueOf(new int[]{50000,25000,100000}[i]),new int[]{24,6,36}[i],110,12,0,false,99,0,99999,true),
        new Stage(0,BigDecimal.ZERO,999,100,99,new BigDecimal("999999999"),999,9999,99,0,false,99,0,99999,true),
        new Stage(new int[]{680,660,640}[i],BigDecimal.ZERO,new int[]{45,45,50}[i],90,2,BigDecimal.valueOf(new int[]{50000,25000,100000}[i]),999,110,99,0,false,6,6,30,false));}
    /**
     * Validates the rule form while retaining mandatory consent, suppression and bureau-identity
     * requirements.
     */
    public static void validate(RuleSet rules){if(rules==null||rules.id()==null||!rules.id().matches("[A-Za-z0-9_.:-]{1,60}")||rules.version()<1)throw new Problem(422,"Versioned rules required");try{java.time.Instant.parse(rules.createdAt());}catch(Exception e){throw new Problem(422,"Rule timestamp required");}validate(rules.underwriting());validate(rules.marketing());validate(rules.bureau());for(var s:List.of(rules.underwriting(),rules.marketing()))if(s.maximumInquiries()!=99||s.minimumHistoryMonths()!=0||s.maximumReportAgeDays()!=99999||!s.allowBankruptcy())throw new Problem(422,"Bureau observations can only be configured in the bureau stage");var b=rules.bureau();if(b.minimumTenureMonths()!=0||b.excludeExistingProduct()||b.maximumVehicleAge()!=99||b.incomeMultiple()!=999)throw new Problem(422,"Tenure, ownership, vehicle age and income multiple belong to customer-data stages");}
    /**
     * Validates the rule form while retaining mandatory consent, suppression and bureau-identity
     * requirements.
     */
    public static void validate(Stage s){if(s==null)throw new Problem(422,"All three rule stages are required");range(s.minimumScore(),0,850);if(s.minimumScore()>0&&s.minimumScore()<300)throw new Problem(422,"Minimum score must be zero (disabled) or 300-850");money(s.minimumIncomeUsd());money(s.maximumAmountUsd());range(s.maximumDtiPct(),0,999);range(s.maximumUtilizationPct(),0,100);range(s.maximumDelinquencies(),0,99);range(s.incomeMultiple(),1,999);range(s.maximumLtvPct(),1,9999);range(s.maximumVehicleAge(),0,99);range(s.minimumTenureMonths(),0,1200);range(s.maximumInquiries(),0,99);range(s.minimumHistoryMonths(),0,1200);range(s.maximumReportAgeDays(),0,99999);}
    /** Rejects a numeric value outside the supported inclusive bounds. */
    private static void range(int n,int low,int high){if(n<low||n>high)throw new Problem(422,"Rule value outside supported range "+low+"-"+high);}
    /** Checks that a monetary policy value is within the supported range. */
    private static void money(BigDecimal n){if(n==null||n.signum()<0||n.compareTo(new BigDecimal("999999999"))>0||n.stripTrailingZeros().scale()>2)throw new Problem(422,"Rule money must be 0-999999999 USD with at most two decimals");}
}

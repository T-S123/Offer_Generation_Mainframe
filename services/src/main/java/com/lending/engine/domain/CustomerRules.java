/**
 * Strict customer/channel input integrity and prescreen rules; numerical credit thresholds remain in
 * COBOL.
 */
package com.lending.engine.domain;

import static com.lending.engine.domain.Model.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * Strict customer/channel input integrity and prescreen rules; numerical credit thresholds remain in
 * COBOL.
 */
public final class CustomerRules {
    /** Prevents instantiation of this utility-only type. */
    private CustomerRules() {}
    /** Checks customer input types, financial ranges and supported channel preferences. */
    public static CustomerInput validate(CustomerInput c) {
        require(c != null, "customer is required");
        if(c.preferredChannels()!=null){require(c.preferredChannels().size()<=3&&c.preferredChannels().stream().allMatch(x->x!=null&&Set.of("EMAIL","SMS","POSTAL").contains(x)),"Use preferredChannels EMAIL, SMS, POSTAL in preference order; empty disables outbound");require(new HashSet<>(c.preferredChannels()).size()==c.preferredChannels().size(),"Duplicate preferred channel");}
        text(c.displayName(), "displayName", 60, true);
        text(c.externalReference(), "externalReference", 60, false);
        money(c.monthlyIncomeUsd(), "monthlyIncomeUsd");
        money(c.monthlyDebtPaymentsUsd(), "monthlyDebtPaymentsUsd");
        money(c.personalLoanAmountUsd(), "personalLoanAmountUsd");
        money(c.requestedCardLimitUsd(), "requestedCardLimitUsd");
        money(c.autoLoanAmountUsd(), "autoLoanAmountUsd");
        money(c.vehicleValueUsd(), "vehicleValueUsd");
        money(c.depositBalanceUsd(), "depositBalanceUsd");
        money(c.monthlySpendUsd(), "monthlySpendUsd");
        integer(c.creditScore(), "creditScore", 300, 850);
        integer(c.delinquencies12m(), "delinquencies12m", 0, 99);
        integer(c.vehicleAgeYears(), "vehicleAgeYears", 0, 99);
        integer(c.bankingTenureMonths(), "bankingTenureMonths", 0, 1200);
        if (c.creditUtilizationPct() != null) {
            money(c.creditUtilizationPct(), "creditUtilizationPct");
            require(c.creditUtilizationPct().compareTo(new BigDecimal("100")) <= 0,
                "creditUtilizationPct must be between 0 and 100");
        }
        if (c.existingProducts() != null) {
            require(c.existingProducts().size() <= 6, "existingProducts has too many entries");
            Set<String> allowed = Set.of("PERSONAL_LOAN", "CREDIT_CARD", "AUTO_LOAN", "CHECKING", "SAVINGS", "MORTGAGE");
            require(c.existingProducts().stream().allMatch(x -> x != null && allowed.contains(x)), "Unknown existingProducts value");
            require(new HashSet<>(c.existingProducts()).size() == c.existingProducts().size(), "Duplicate existingProducts value");
        }
        return c;
    }
    /** Checks a nullable monetary input for nonnegative range and supported precision. */
    public static void money(BigDecimal value, String name) {
        if (value == null) return;
        require(value.signum() >= 0 && value.compareTo(new BigDecimal("999999999.99")) <= 0,
            name + " must be between 0 and 999999999.99");
        require(value.stripTrailingZeros().scale() <= 2, name + " supports at most two decimal places");
    }
    /** Checks a nullable integer input against its allowed bounds. */
    public static void integer(Integer value, String name, int min, int max) {
        if (value != null) require(value >= min && value <= max, name + " must be between " + min + " and " + max);
    }
    /** Checks requiredness and length for a customer text field. */
    public static void text(String value, String name, int max, boolean required) {
        if (value == null) { require(!required, name + " is required"); return; }
        require(!value.isBlank() && value.length() <= max && value.chars().noneMatch(Character::isISOControl),
            name + " must contain 1-" + max + " printable characters");
    }
    /** Rejects an invalid condition with a domain validation error. */
    public static void require(boolean ok, String message) { if (!ok) throw new Problem(422, message); }
    /** Collects the reasons a customer decision cannot enter the pre-screen population. */
    public static List<String> exclusionReasons(Profile profile, Decision d, LocalDate today) {
        List<String> reasons = new ArrayList<>();
        if (profile.data().prescreenOptOut() == null) reasons.add("PRESCREEN_PREFERENCE_UNKNOWN");
        else if (profile.data().prescreenOptOut()) reasons.add("PRESCREEN_OPT_OUT");
        if (d == null) reasons.add("NO_CURRENT_ASSESSMENT");
        else {
            if (d.profileVersion() != profile.version()) reasons.add("STALE_PROFILE_ASSESSMENT");
            if (LocalDate.parse(d.validUntil()).isBefore(today)) reasons.add("ASSESSMENT_EXPIRED");
            if (d.status() != Status.ELIGIBLE) {
                reasons.add(d.status().name());
                reasons.addAll(d.reasons());
            }
        }
        return reasons.stream().distinct().toList();
    }
}

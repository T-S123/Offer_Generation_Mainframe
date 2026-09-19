/**
 * Deterministic synthetic financial features for cold-start cohort training; no real customers or observed
 * acceptance labels.
 */
package com.lending.offers.domain;
import com.lending.offers.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * Deterministic synthetic financial features for cold-start cohort training; no real customers or observed
 * acceptance labels.
 */
public final class BootstrapPopulation {
    public static final String BATCH="LOCAL-BOOTSTRAP-2026-001";
    /** Prevents instantiation of this utility-only type. */
    private BootstrapPopulation(){}
    /** Generates deterministic synthetic financial features for cold-start cohort training. */
    public static List<JsonNode> generate(){var random=new Random(73107);var rows=new ArrayList<JsonNode>();for(int i=0;i<1000;i++){double income=1500+random.nextInt(18000);var n=Json.MAPPER.createObjectNode();n.put("customerId",String.format(Locale.ROOT,"bootstrap-%04d",i));n.put("origin","SYNTHETIC");n.put("monthlyIncomeUsd",income);n.put("monthlyDebtPaymentsUsd",Math.round(income*random.nextDouble()*.65));n.put("creditUtilizationPct",random.nextInt(101));n.put("bankingTenureMonths",random.nextInt(241));n.put("depositBalanceUsd",Math.round(income*random.nextDouble()*8));n.put("monthlySpendUsd",Math.round(income*(.1+random.nextDouble()*.8)));rows.add(n);}return rows;}
}

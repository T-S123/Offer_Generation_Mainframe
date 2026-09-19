/**
 * Customer-fit-first bounded term search; acceptance is a versioned utility simulation, never a calibrated
 * prediction or credit decision.
 */
package com.lending.offers.domain;
import com.lending.offers.Json;

import static com.lending.offers.domain.Model.*;
import static com.lending.offers.domain.Cohorts.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
import java.util.*;

/**
 * Customer-fit-first bounded term search; acceptance is a versioned utility simulation, never a calibrated
 * prediction or credit decision.
 */
public final class Personalizer {
    public static final String SCORING="CUSTOMER-FIT-SIM-1";
    /** Prevents instantiation of this utility-only type. */
    private Personalizer(){}
    /** Calculates affordability, cost, fit and uncalibrated acceptance metrics for proposed offer terms. */
    public static Metrics score(Terms t,Terms original,String product,JsonNode f,double[] w){
        double principal=t.amountUsd().doubleValue(),apr=t.aprPct().doubleValue()/1200,fee=t.annualFeeUsd().doubleValue(),payment,cost;
        if(product.equals("CREDIT_CARD")){payment=principal*.03+fee/12;cost=principal*t.aprPct().doubleValue()/100+fee;}
        else{payment=apr==0?principal/t.termMonths():principal*apr/(1-Math.pow(1+apr,-t.termMonths()));cost=payment*t.termMonths()-principal+fee*t.termMonths()/12d;payment+=fee/12;}
        double income=Math.max(1,n(f,"monthlyIncomeUsd",2000)),debt=n(f,"monthlyDebtPaymentsUsd",income*.4);double affordable=clamp(1-(debt+payment)/income,0,1),match=clamp(1-Math.abs(principal-original.amountUsd().doubleValue())/Math.max(1,original.amountUsd().doubleValue()),0,1),costFit=1/(1+Math.max(0,cost)/principal),termFit=product.equals("CREDIT_CARD")?1:clamp(1-t.termMonths()/144d,0,1);
        double fit=100*(w[0]*affordable+w[1]*costFit+w[2]*match+w[3]*termFit),acceptance=1/(1+Math.exp(-(fit-65)/12));return new Metrics(fit,acceptance,payment,cost,affordable,match,costFit,termFit);
    }
    /**
     * Enumerates distinct candidate terms within the catalog bounds and demonstration personalization
     * envelope.
     */
    public static List<Candidate> candidates(JsonNode context,CohortModel model){
        Terms original=terms(context.path("original"));String product=context.path("response").path("qualification").path("product").asText();var f=context.path("features");var policy=context.path("policy");var catalog=context.path("response").path("qualifiedOffer").path("data");double[] weights=weights(model,f);double floor=policy.path("minimumAprPct").path(product).asDouble(),discount=policy.path("maximumAprReductionPct").asDouble(),amountChange=policy.path("maximumAmountChangePct").asDouble()/100;
        var candidates=new LinkedHashMap<String,Candidate>();int delta=policy.path("maximumTermChangeMonths").asInt();int[] terms=product.equals("CREDIT_CARD")?new int[]{0}:new int[]{original.termMonths(),Math.max(policy.path("minimumTermMonths").asInt(),original.termMonths()-delta),Math.min(policy.path("maximumTermMonths").asInt(),original.termMonths()+delta)};
        for(double amount:new double[]{1-amountChange,1,1+amountChange})for(double rate:new double[]{0,.25,.5,1})for(double fee:new double[]{1,.5,0})for(int term:terms){
            BigDecimal principal=money(original.amountUsd().doubleValue()*amount);if(principal.compareTo(catalog.path("minimumAmountUsd").decimalValue())<0||principal.compareTo(catalog.path("maximumAmountUsd").decimalValue())>0)continue;
            double apr=Math.max(floor,original.aprPct().doubleValue()-discount*rate);if(apr>original.aprPct().doubleValue())continue;var t=new Terms(principal,money(apr),money(original.annualFeeUsd().doubleValue()*fee),term);if(t.equals(original))continue;var candidate=new Candidate(t,score(t,original,product,f,weights));candidates.put(Json.write(t),candidate);
        }
        double baseline=score(original,original,product,f,weights).fitScore();return candidates.values().stream().filter(c->c.metrics().fitScore()>baseline+.01).sorted(Comparator.comparingDouble((Candidate c)->c.metrics().fitScore()).reversed().thenComparing(c->Json.write(c.terms()))).toList();
    }
    /** Rounds a computed financial value to two decimal places. */
    public static BigDecimal money(double n){return BigDecimal.valueOf(n).setScale(2,RoundingMode.HALF_UP);}
    /** Calculates the financial and fit changes from the original offer to a candidate. */
    public static Map<String,Double> deltas(Metrics a,Metrics b){return Map.of("fitScore",b.fitScore()-a.fitScore(),"simulatedAcceptancePercentagePoints",100*(b.simulatedAcceptance()-a.simulatedAcceptance()),"monthlyPaymentUsd",b.monthlyPaymentUsd()-a.monthlyPaymentUsd(),"totalCostUsd",b.totalCostUsd()-a.totalCostUsd());}
}

/**
 * Immutable Business experiments snapshot inputs, rules and catalog versions independently of active
 * customers.
 */
package com.lending.engine.simulation.domain;
import com.lending.engine.domain.Model.*;
import com.lending.engine.bureau.domain.Bureau.Facts;
import com.lending.engine.marketing.domain.Marketing.*;
import com.lending.engine.simulation.domain.BusinessRules.RuleSet;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * Immutable Business experiments snapshot inputs, rules and catalog versions independently of active
 * customers.
 */
public final class Simulation {
    /** Prevents instantiation of this utility-only type. */
    private Simulation(){}
    /** Carries person data for simulation operations. */
    public record Person(String id,CustomerInput customer,Facts bureau,boolean suppressed) {}
    /** Carries population data for simulation operations. */
    public record Population(String id,int count,long seed,String asOf,String generatorVersion,List<Person> people) {}
    /** Carries generate data for simulation operations. */
    public record Generate(int count,long seed) {}
    /** Carries draft data for simulation operations. */
    public record Draft(String id,int version,String createdAt,String name,Offer baseline,Campaign campaign,Policy policy,OfferInput offer,RuleSet rules) {}
    /** Carries create draft data for simulation operations. */
    public record CreateDraft(String name,String baselineOfferId,String campaignId) {}
    /** Carries edit draft data for simulation operations. */
    public record EditDraft(int expectedVersion,String name,OfferInput offer,RuleSet rules) {}
    /** Carries run request data for simulation operations. */
    public record RunRequest(String draftId,int draftVersion,String populationId,long seed) {}
    /** Carries stage result data for simulation operations. */
    public record StageResult(String status,List<String> reasons) {
        /**
         * Checks whether the supplied result satisfies the eligibility conditions required by this
         * boundary.
         */
        public boolean eligible(){return status.equals("ELIGIBLE");}}
    /** Carries outcome data for simulation operations. */
    public record Outcome(StageResult underwriting,StageResult marketing,StageResult bureau) {
        /**
         * Checks whether the supplied result satisfies the eligibility conditions required by this
         * boundary.
         */
        public boolean eligible(){return underwriting.eligible()&&marketing.eligible()&&bureau.eligible();}}
    /** Carries row data for simulation operations. */
    public record Row(String customerId,Outcome baseline,Outcome candidate,JsonNode analysis) {}
    /** Carries run data for simulation operations. */
    public record Run(String id,RunRequest request,Draft draft,String status,String createdAt,String completedAt,String error,JsonNode report) {}
    /** Carries review data for simulation operations. */
    public record Review(String note,boolean confirm) {}
    /** Carries publication data for simulation operations. */
    public record Publication(String runId,String fingerprint,String offerId,String campaignId,String ruleVersion,String publishedAt,String reviewNote) {}
}

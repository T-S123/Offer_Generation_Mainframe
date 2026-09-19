/**
 * Marketing persistence shares the customer unit of work and exposes bounded qualification scans for
 * pre-screen offer storage.
 */
package com.lending.engine.marketing.application;

import com.lending.engine.marketing.domain.Marketing.*;
import com.lending.engine.domain.Model.Page;
import java.util.*;

/**
 * Marketing persistence shares the customer unit of work and exposes bounded qualification scans for
 * pre-screen offer storage.
 */
public final class MarketingPorts {
    /** Prevents instantiation of this utility-only type. */
    private MarketingPorts() {}
    /** Defines the qualifier boundary used by application workflows. */
    public interface Qualifier { 
        /**
         * Evaluates ordered marketing facts with explicit allocation context and returns qualification
         * reasons.
         */
        List<RuleResult> evaluate(List<RuleInput> inputs); }
    /** Defines the repository boundary used by application workflows. */
    public interface Repository {
        /** Retrieves the current approved catalog offer. */
        Offer offer(String id); 
            /** Lists the current approved catalog offers. */
            List<Offer> offers(); 
            /** Persists a new catalog offer revision while retaining its history. */
            void saveOffer(Offer value); 
            /** Returns the immutable revision history of a catalog offer. */
            List<Offer> offerHistory(String id);
        /** Retrieves the current campaign configuration. */
        Campaign campaign(String id); 
            /** Lists the current campaign configurations. */
            List<Campaign> campaigns(); 
            /** Persists a new campaign revision while retaining its history. */
            void saveCampaign(Campaign value); 
            /** Returns the immutable revision history of a campaign. */
            List<Campaign> campaignHistory(String id);
        /** Returns the active policy configuration used by the workflow. */
        Policy policy(); 
            /** Persists a new marketing policy revision while retaining its history. */
            void savePolicy(Policy value); 
            /** Returns the revision history of the marketing policy. */
            List<Policy> policyHistory();
        /** Retrieves the current suppression entry for a customer. */
        Suppression suppression(String customerId); 
            /** Lists configured customer suppression entries. */
            List<Suppression> suppressions(); 
            /** Persists a new customer suppression revision while retaining its history. */
            void saveSuppression(Suppression value); 
            /** Returns the revision history of a customer suppression entry. */
            List<Suppression> suppressionHistory(String id);
        /** Loads the requested marketing qualification run. */
        Run marketingRun(String id); 
            /** Finds the marketing run associated with an idempotent request identifier. */
            Run requestedRun(String requestId); 
            /** Persists the qualification run state and its audit information. */
            void saveRun(Run run);
        /** Lists stored marketing qualification runs. */
        Page<RunSummary> marketingRuns(int offset,int limit);
        /** Retrieves the offer qualification results belonging to a marketing run. */
        List<Qualification> qualifications(String runId); 
            /** Persists the offer-level results of a qualification run. */
            void saveQualifications(String runId,List<Qualification> rows);
        /** Returns qualification results associated with a Risk ID. */
        List<Qualification> qualificationsForRisk(String runId,String riskId);
        /** Returns a bounded page of qualifications with stable pagination. */
        List<com.lending.engine.bureau.domain.Bureau.SourceRow> qualificationPage(String runId,int after,int limit);
        /** Carries stored match data for marketing ports operations. */
        record StoredMatch(String runId,int ordinal,Qualification qualification) {}
        /** Scans finalized qualifications for downstream pre-screen offer recovery. */
        List<StoredMatch> qualificationScan(String afterRun,int afterOrdinal,int limit);
        /** Retrieves the reservations backing a finalized qualification source. */
        List<Reservation> sourceReservations(String runId,String customerId,String campaignId);
        /** Lists qualification reservations and their retained history. */
        List<Reservation> reservations(); 
            /** Persists a qualification reservation without discarding prior allocation history. */
            void saveReservation(Reservation value);
    }
}

/**
 * Local transaction boundary includes atomic publication receipts; isolated Business populations never
 * enter this store.
 */
package com.lending.engine.application;

import com.lending.engine.domain.Model.*;
import java.util.*;
import java.util.function.Function;

/**
 * Local transaction boundary includes atomic publication receipts; isolated Business populations never
 * enter this store.
 */
public final class Ports {
    /** Prevents instantiation of this utility-only type. */
    private Ports() {}
    /** Defines the underwriter boundary used by application workflows. */
    public interface Underwriter { 
        /** Evaluates customer inputs and returns product-level underwriting results. */
        List<List<RuleResult>> evaluate(List<CustomerInput> customers); }
    /** Defines the store boundary used by application workflows. */
    public interface Store extends AutoCloseable {
        /** Runs a read-only action against the customer and marketing repository. */
        <T> T read(Function<Session,T> action);
        /** Runs an atomic write action against the customer and marketing repository. */
        <T> T write(Function<Session,T> action);
        /** Provides an optional no-op cleanup hook for store implementations without owned resources. */
        default void close() {}
    }
    /** Defines the session boundary used by application workflows. */
    public interface Session extends com.lending.engine.marketing.application.MarketingPorts.Repository {
        /** Retrieves the publication receipt associated with a reviewed simulation run. */
        com.lending.engine.simulation.domain.Simulation.Publication publication(String runId);
        /** Persists the receipt linking reviewed simulation evidence to a published catalog change. */
        void savePublication(com.lending.engine.simulation.domain.Simulation.Publication publication);
        /** Retrieves the customer record required by this workflow. */
        Customer customer(String id);
        /** Persists the customer and associated profile and decision history. */
        void saveCustomer(Customer customer);
        /** Loads the customer synthetic contact details and delivery preferences. */
        Contact contact(String customerId);
        /** Loads the latest automatic processing state for a customer. */
        Pipeline pipeline(String customerId);
        /** Returns a bounded batch of customer pipelines due for background processing. */
        List<Pipeline> pendingPipelines(long now,int limit);
        /** Persists the customer pipeline state and its next scheduled processing time. */
        void savePipeline(Pipeline pipeline,long nextAt);
        /** Returns a bounded page of customer summaries using the requested batch filter. */
        Page<CustomerSummary> customers(String batchId, int offset, int limit);
        /** Loads the customers belonging to a synthetic batch for population evaluation. */
        List<Customer> populationCustomers(String batchId);
        /** Finds an imported decision by its source-system identity. */
        Decision importedDecision(String sourceSystem, String sourceDecisionId);
        /** Records the source-system decision identity used to deduplicate imports. */
        void indexImport(Decision decision);
        /** Retrieves the requested synthetic-generation job and its progress. */
        Job job(String id);
        /** Lists recorded generation or training jobs and their outcomes. */
        List<Job> jobs();
        /** Persists the generation job state, progress and outcome. */
        void saveJob(Job job);
        /** Retrieves the specified pre-screen or simulation population. */
        Population population(String id);
        /** Lists the available population snapshots. */
        List<Population> populations();
        /** Persists a population snapshot together with its exact eligible members. */
        void savePopulation(Population population, List<Member> members);
        /** Returns a page of members and their decision references from a population snapshot. */
        Page<Member> members(String id, int offset, int limit);
    }
}

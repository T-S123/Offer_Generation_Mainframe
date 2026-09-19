/**
 * Source evidence stays locked through response persistence, while the response domain remains independent
 * of H2 and PostgreSQL.
 */
package com.lending.engine.response.application;

import com.lending.engine.bureau.domain.Bureau.Reference;
import com.lending.engine.response.domain.DecisionResponse.Evidence;
import java.util.function.Function;

/**
 * Source evidence stays locked through response persistence, while the response domain remains independent
 * of H2 and PostgreSQL.
 */
public final class ResponsePorts {
    /** Prevents instantiation of this utility-only type. */
    private ResponsePorts() {}
    /** Defines the source boundary used by application workflows. */
    public interface Source { 
        /** Runs an action with current source evidence under the source-store transaction boundary. */
        <T>T withEvidence(Reference reference,Function<Evidence,T> action); }
}

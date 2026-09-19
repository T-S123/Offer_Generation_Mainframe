/** Domain-facing contracts keep HTTP, SQL, Kafka and COBOL transport outside use cases. */
package com.lending.engine.bureau.application;

import com.lending.engine.bureau.domain.Bureau.*;
import java.util.List;
import java.util.function.Function;

/** Domain-facing contracts keep HTTP, SQL, Kafka and COBOL transport outside use cases. */
public final class BureauPorts {
    /** Prevents instantiation of this utility-only type. */
    private BureauPorts() {}
    /** Defines the source boundary used by application workflows. */
    public interface Source {
        /** Loads current customer and offer evidence for a finalized marketing qualification. */
        Snapshot current(Reference source);
        /** Returns a bounded page of finalized source qualifications for bureau batch expansion. */
        List<SourceRow> page(String runId,int after,int limit);
        /**
         * Runs an action while the referenced qualification remains protected by current source
         * validation.
         */
        <T> T withCurrent(Reference source,Function<Snapshot,T> action);
    }
    /** Defines the credit gateway boundary used by application workflows. */
    public interface CreditGateway {
        /** Obtains a credit decision for the supplied qualified customer and offer terms. */
        Assessment assess(CreditInput request);
        /** Loads the independent bureau report associated with a customer. */
        BureauProfile profile(String customerId);
        /** Saves a version-checked independent bureau report import or edit. */
        BureauProfile change(String customerId,ProfileChange change);
        /** Returns the opaque bureau subject identifier mapped to a customer. */
        default String subjectId(String customerId){return profile(customerId).subjectId();}
        /** Returns independent report revisions for a mapped bureau subject. */
        default List<BureauProfile> history(String customerId,int after){throw new UnsupportedOperationException("History adapter unavailable");}
    }
    /** Defines the credit policy boundary used by application workflows. */
    public interface CreditPolicy { 
        /** Evaluates the credit request against the supplied independent report. */
        Decision evaluate(CreditInput input,BureauProfile profile); }
    /** Carries decision data for bureau ports operations. */
    public record Decision(String outcome,List<String> reasons,String policyVersion) {}
}

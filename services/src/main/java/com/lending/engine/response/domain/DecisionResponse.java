/**
 * Step 4 contract and eligibility projection of the existing COBOL decisions; never issues an offer or a
 * new credit decision.
 */
package com.lending.engine.response.domain;

import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.marketing.domain.Marketing.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Step 4 contract and eligibility projection of the existing COBOL decisions; never issues an offer or a
 * new credit decision.
 */
public final class DecisionResponse {
    /** Prevents instantiation of this utility-only type. */
    private DecisionResponse() {}
    public static final String POLICY="DRS-2026-001";
    public static final String QUALIFICATIONS="marketing.qualification.updated.v1";
    public static final String DECISIONS="bureau.decision.published.v1";
    /** Carries evidence data for decision response operations. */
    public record Evidence(Qualification qualification,Offer offer,String validUntil,String failure) {}
    /** Carries decision data for decision response operations. */
    public record Decision(String id,String outcome,List<String> reasons,String policyVersion,String assessedAt,String validUntil) {}
    /** Carries response data for decision response operations. */
    public record Response(String id,long version,String requestId,Reference source,String customerId,
        Qualification qualification,Offer qualifiedOffer,BigDecimal assessedAmountUsd,String currency,
        Decision bureauDecision,boolean marketingEligible,String status,List<String> reasons,
        String validUntil,String evaluatedAt,String eligibilityPolicyVersion) {}
    /** Carries event data for decision response operations. */
    public record Event(String eventId,int schemaVersion,String type,String occurredAt,Response response) {}
    /** Carries decision event data for decision response operations. */
    public record DecisionEvent(String eventId,int schemaVersion,String type,String occurredAt,String requestId,
        Reference source,String customerId,String processingStatus,Decision bureauDecision,String failure) {}
    /** Carries page data for decision response operations. */
    public record Page<T>(List<T> items,String nextAfter) {}

    /** Builds the minimized qualification evidence exposed in a decision response. */
    public static Decision brief(Assessment a){return a==null?null:new Decision(a.id(),a.outcome(),a.reasons(),a.policyVersion(),a.assessedAt(),a.validUntil());}
    /** Creates the initial marketing qualification response from a completed bureau decision. */
    public static Response initial(String id,long version,Result result,Evidence evidence,Instant now){
        var a=result.decision();String until=evidence.validUntil();
        if(a!=null&&(until==null||Instant.parse(a.validUntil()).isBefore(Instant.parse(until))))until=a.validUntil();
        String status;List<String> reasons;
        if(!result.status().equals("COMPLETED")||a==null){status=result.status().equals("INVALIDATED")?"INVALIDATED":"FAILED";reasons=List.of(result.reason()==null?"BUREAU_RESULT_UNAVAILABLE":result.reason());}
        else if(!a.outcome().equals("APPROVED")){status=a.outcome().equals("DECLINED")?"DECLINED":"REVIEW";reasons=a.reasons();}
        else if(until==null||!Instant.parse(until).isAfter(now)){status="EXPIRED";reasons=List.of("QUALIFICATION_OR_DECISION_EXPIRED");}
        else if(evidence.failure()!=null){status="REVOKED";reasons=List.of(evidence.failure());}
        else{status="ELIGIBLE";reasons=List.of("BUREAU_APPROVED_AND_STEP2_VALID");}
        return new Response(id,version,result.requestId(),result.source(),evidence.qualification().customerId(),
            evidence.qualification(),evidence.offer(),a==null?null:a.input().amountUsd(),"USD",brief(a),status.equals("ELIGIBLE"),status,reasons,until,now.toString(),POLICY);
    }
    /** Recomputes marketing eligibility from current source evidence without issuing a new credit decision. */
    public static Response revalidate(Response prior,Evidence evidence,Instant now){
        if(!prior.marketingEligible())return prior;String status=null,reason=null;
        if(prior.validUntil()==null||!Instant.parse(prior.validUntil()).isAfter(now)){status="EXPIRED";reason="QUALIFICATION_OR_DECISION_EXPIRED";}
        else if(evidence.failure()!=null){status="REVOKED";reason=evidence.failure();}
        if(status==null)return prior;
        return new Response(prior.id(),prior.version()+1,prior.requestId(),prior.source(),prior.customerId(),prior.qualification(),prior.qualifiedOffer(),prior.assessedAmountUsd(),prior.currency(),prior.bureauDecision(),false,status,List.of(reason),prior.validUntil(),now.toString(),POLICY);
    }
}

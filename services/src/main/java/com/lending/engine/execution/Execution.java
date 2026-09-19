/**
 * Campaign domain: one versioned package per customer/campaign, qualified alternatives and a separately
 * counted outbound dispatch.
 */
package com.lending.engine.execution;
import com.lending.engine.domain.Model.Contact;
import com.lending.engine.offers.OfferSource.Terms;
import java.util.*;

/**
 * Campaign domain: one versioned package per customer/campaign, qualified alternatives and a separately
 * counted outbound dispatch.
 */
public final class Execution {
    /** Prevents instantiation of this utility-only type. */
    private Execution(){}
    /** Carries policy data for execution operations. */
    public record Policy(String version,long cooldownSeconds,int windowDays,int maximumPackages) {}
    /** Carries alternative data for execution operations. */
    public record Alternative(String responseId,long responseVersion,String riskId,String catalogOfferId,int catalogOfferVersion,String kind,Terms terms,double fitScore,double simulatedAcceptance,String variantQualificationId,String validUntil) {}
    /** Carries package data for execution operations. */
    public record Package(String id,long version,String customerId,String campaignId,String sourceKey,String channel,Contact contact,String leadResponseId,String leadKind,String selectionId,List<Alternative> alternatives,String policyVersion,String status,String reason,String validUntil,String createdAt) {
        /** Copies a campaign package with the supplied version, lifecycle state and reason. */
        public Package state(long v,String state,String reason){return new Package(id,v,customerId,campaignId,sourceKey,channel,contact,leadResponseId,leadKind,selectionId,alternatives,policyVersion,state,reason,validUntil,createdAt);}
    }
    /** Carries history data for execution operations. */
    public record History(boolean sameDispatch,long secondsSinceLast,int windowCount) {}
}

/**
 * Authoritative variants retain each catalog offer's published underwriting, marketing and bureau rules;
 * Business models never replace the active personalization model implicitly.
 */
package com.lending.engine.offers;

import com.lending.engine.application.CustomerEngine;
import com.lending.engine.bureau.application.BureauEngine;
import com.lending.engine.bureau.domain.Bureau;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.domain.Model.*;
import com.lending.engine.infrastructure.Json;
import com.lending.engine.response.application.ResponseEngine;
import com.lending.engine.response.domain.DecisionResponse.Response;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.*;
import java.math.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Authoritative variants retain each catalog offer's published underwriting, marketing and bureau rules;
 * Business models never replace the active personalization model implicitly.
 */
public final class OfferSource {
    /** Carries terms data for offer source operations. */
    public record Terms(BigDecimal amountUsd,BigDecimal aprPct,BigDecimal annualFeeUsd,int termMonths) {
        /**
         * Creates a terms value from the supplied fields, filling omitted optional fields with their
         * compatibility defaults.
         */
        public Terms {if(amountUsd!=null)amountUsd=amountUsd.setScale(2);if(aprPct!=null)aprPct=aprPct.setScale(2);if(annualFeeUsd!=null)annualFeeUsd=annualFeeUsd.setScale(2);}
    }
    /** Carries policy data for offer source operations. */
    public record Policy(String version,Map<Product,BigDecimal> minimumAprPct,BigDecimal maximumAprReductionPct,int maximumAmountChangePct,int minimumTermMonths,int maximumTermMonths,int maximumTermChangeMonths) {}
    /** Carries features data for offer source operations. */
    public record Features(String customerId,int profileVersion,String origin,String creditInformationSource,BigDecimal monthlyIncomeUsd,BigDecimal monthlyDebtPaymentsUsd,BigDecimal creditUtilizationPct,Integer bankingTenureMonths,BigDecimal depositBalanceUsd,BigDecimal monthlySpendUsd) {}
    /** Carries context data for offer source operations. */
    public record Context(Response response,Features features,Terms original,Policy policy,String policyVersion) {}
    /** Carries proposal data for offer source operations. */
    public record Proposal(String requestId,String responseId,long responseVersion,String policyVersion,Terms terms) {}
    /** Carries approval data for offer source operations. */
    public record Approval(String id,String responseId,long responseVersion,String policyVersion,Terms terms,boolean approved,List<String> checks,String bureauDecisionId,String underwritingPolicyVersion,String validUntil) {}
    private final CustomerEngine customers;private final BureauEngine bureau;private final ResponseEngine responses;private final Path binary;private final Clock clock;
    private final Policy policy;private final String policyVersion;
    private final com.lending.engine.simulation.infrastructure.CobolBusinessPolicy business;
    /** Initializes offer source with the supplied configuration and dependencies. */
    public OfferSource(CustomerEngine customers,BureauEngine bureau,ResponseEngine responses,Path root,Clock clock){
        this.customers=customers;this.bureau=bureau;this.responses=responses;this.clock=clock;binary=root.resolve("build/liofbatch");
        business=new com.lending.engine.simulation.infrastructure.CobolBusinessPolicy(root);
        try{policy=Json.read(Files.readString(root.resolve("app/data/offer-personalization-policy.json")),Policy.class);}catch(Exception e){throw new IllegalStateException("Cannot read offer policy",e);}
        if(!"MOF-2026-001".equals(policy.version())||policy.minimumAprPct()==null||policy.minimumAprPct().size()!=3||policy.maximumAmountChangePct()<0||policy.maximumAmountChangePct()>25||policy.minimumTermMonths()<1||policy.maximumTermMonths()>120||policy.minimumTermMonths()>policy.maximumTermMonths()||policy.maximumTermChangeMonths()<0||policy.maximumTermChangeMonths()>60)throw new IllegalArgumentException("Invalid demonstration offer policy");
        money(policy.maximumAprReductionPct(),100);for(var p:Product.values())money(policy.minimumAprPct().get(p),100);
        policyVersion=policy.version()+"-"+hash(policy).substring(0,12);
    }
    /** Lists completed synthetic batches that may supply active model-training features. */
    public Object simulationBatches(){return customers.jobs().stream().filter(j->j.status().equals("COMPLETED")&&j.count()>=60).map(j->Map.of("id",j.id(),"seed",j.seed(),"count",j.count())).toList();}
    /** Returns the versioned personalization constraints supported by the engine. */
    public Object policy(){return Map.of("policy",policy,"policyVersion",policyVersion);}
    /** Returns a bounded response page used by marketing recovery. */
    public Object responses(String after,int limit){return responses.list(null,after,limit);}
    /** Returns pre-screen catalog matches with their current qualification state. */
    public Object prescreens(String after,int limit){return ((com.lending.engine.bureau.application.MarketingSource)bureau.source).prescreens(after,limit);}
    /** Extracts minimized financial features from eligible synthetic customers. */
    public Object features(String batchId,int offset,int limit){
        Bureau.id(batchId,"batchId");var job=customers.job(batchId);if(!job.status().equals("COMPLETED"))throw new Problem(409,"Complete the Step 1 simulation before training");
        var page=customers.list(batchId,offset,limit);var rows=new ArrayList<Features>();
        for(var summary:page.items()){var p=customers.get(summary.id()).current();if(!p.origin().equals("SYNTHETIC"))throw new Problem(409,"Training batch was edited; generate a new synthetic batch");rows.add(features(p));}
        return Map.of("items",rows,"total",page.total(),"offset",offset,"limit",limit,"seed",job.seed(),"batchId",batchId);
    }
    /** Extracts minimized financial features from eligible synthetic customers. */
    private static Features features(Profile p){var d=p.data();return new Features(p.customerId(),p.version(),p.origin(),p.creditInformationSource(),d.monthlyIncomeUsd(),d.monthlyDebtPaymentsUsd(),d.creditUtilizationPct(),d.bankingTenureMonths(),d.depositBalanceUsd(),d.monthlySpendUsd());}
    /** Checks whether the customer source can be used for offer personalization. */
    private Response eligible(String id,long version){var r=responses.get(id);if(!r.marketingEligible()||version!=r.version()||!Instant.parse(r.validUntil()).isAfter(clock.instant()))throw new Problem(409,"Source qualification changed or expired");return r;}
    /** Builds current qualification, catalog terms, feature and policy context for the marketing service. */
    public Context context(String id){var r=responses.get(id);eligible(id,r.version());return bureau.source.withCurrent(r.source(),s->new Context(r,features(s.customer()),new Terms(r.assessedAmountUsd(),s.offer().data().illustrativeAprPct(),s.offer().data().annualFeeUsd(),s.offer().data().termMonths()),policy,policyVersion));}
    /** Rechecks whether an original or personalized offer remains authoritatively qualified. */
    public Approval check(String id){Bureau.id(id,"approvalId");var a=bureau.queue.db.tx(c->one(c,"SELECT payload FROM offer_variant_qualifications WHERE id=?",Approval.class,id));if(a==null)throw new Problem(404,"Variant qualification not found");eligible(a.responseId(),a.responseVersion());if(!a.approved()||!a.policyVersion().equals(policyVersion)||!Instant.parse(a.validUntil()).isAfter(clock.instant()))throw new Problem(409,"Variant qualification is not current");return a;}
    /** Qualifies exact proposed terms through the envelope, underwriting, marketing and bureau checks. */
    public Approval qualify(Proposal proposal){
        Bureau.id(proposal.requestId(),"requestId");if(proposal.requestId().length()>76)throw new Problem(422,"requestId exceeds 76 characters");validate(proposal.terms());var r=eligible(proposal.responseId(),proposal.responseVersion());if(!policyVersion.equals(proposal.policyVersion()))throw new Problem(409,"Offer policy changed");
        String fingerprint=hash(proposal);var prior=bureau.queue.db.tx(c->{String fp=scalar(c,"SELECT fingerprint FROM offer_variant_qualifications WHERE id=?",proposal.requestId());if(fp!=null&&!fp.equals(fingerprint))throw new Problem(409,"requestId already used for other terms");return one(c,"SELECT payload FROM offer_variant_qualifications WHERE id=?",Approval.class,proposal.requestId());});if(prior!=null)return prior;
        var before=bureau.source.current(r.source());var ctx=context(r.id());String envelope=envelope(ctx,proposal.terms());
        var checks=new ArrayList<String>();checks.add(envelope);String underwritingVersion=null,decisionId=null;boolean approved=false;String until=r.validUntil();
        if(envelope.equals("APPROVED")){
            var node=Json.MAPPER.valueToTree(before.customer().data());String amountField=switch(r.qualification().product()){case PERSONAL_LOAN->"personalLoanAmountUsd";case CREDIT_CARD->"requestedCardLimitUsd";case AUTO_LOAN->"autoLoanAmountUsd";};((com.fasterxml.jackson.databind.node.ObjectNode)node).put(amountField,proposal.terms().amountUsd());
            var input=Json.MAPPER.convertValue(node,CustomerInput.class);RuleResult uw;boolean marketingEligible=true;
            if(before.offer().rules()==null){uw=customers.assessVariant(input).stream().filter(x->x.product()==r.qualification().product()).findFirst().orElseThrow();}else{var rules=before.offer().rules();var u=business.customer(input,r.qualification().product(),rules.underwriting(),"U",false);uw=new RuleResult(r.qualification().product(),Status.valueOf(u.status()),u.reasons(),rules.id()+"-v"+rules.version());var m=business.customer(input,r.qualification().product(),rules.marketing(),"M",false);marketingEligible=m.eligible();checks.add("MARKETING_"+m.status());}
            underwritingVersion=uw.policyVersion();checks.add("UNDERWRITING_"+uw.status());
            if(uw.status()==Status.ELIGIBLE&&marketingEligible){var t=proposal.terms();var assessment=bureau.credit.assess(new CreditInput("OV-"+proposal.requestId(),bureau.credit.subjectId(r.customerId()),r.customerId(),r.qualification().product(),t.amountUsd(),input.monthlyIncomeUsd(),before.customer().creditInformationSource(),input.vehicleValueUsd(),t.termMonths(),t.aprPct(),before.offer().rules()));decisionId=assessment.id();checks.add("BUREAU_"+assessment.outcome());approved=assessment.outcome().equals("APPROVED");if(Instant.parse(assessment.validUntil()).isBefore(Instant.parse(until)))until=assessment.validUntil();}
        }
        var result=new Approval(proposal.requestId(),r.id(),r.version(),policyVersion,proposal.terms(),approved,List.copyOf(checks),decisionId,underwritingVersion,until);
        return bureau.source.withCurrent(r.source(),current->{if(!current.equals(before))throw new Problem(409,"Source changed during variant qualification");eligible(r.id(),r.version());return bureau.queue.db.tx(c->{execute(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",proposal.requestId());String fp=scalar(c,"SELECT fingerprint FROM offer_variant_qualifications WHERE id=?",proposal.requestId());if(fp!=null){if(!fp.equals(fingerprint))throw new Problem(409,"requestId conflict");return one(c,"SELECT payload FROM offer_variant_qualifications WHERE id=?",Approval.class,proposal.requestId());}execute(c,"INSERT INTO offer_variant_qualifications VALUES(?,?,?,?)",proposal.requestId(),fingerprint,r.id(),Json.write(result));return result;});});
    }
    /** Checks proposed financial terms against the catalog and COBOL personalization limits. */
    String envelope(Context ctx,Terms t){
        var offer=ctx.response().qualifiedOffer().data();var base=ctx.original();String product=switch(offer.product()){case PERSONAL_LOAN->"PL";case CREDIT_CARD->"CC";case AUTO_LOAN->"AL";};
        String record=product+cents(base.amountUsd(),11)+cents(base.aprPct(),5)+cents(base.annualFeeUsd(),9)+digits(base.termMonths(),3)+cents(offer.minimumAmountUsd(),11)+cents(offer.maximumAmountUsd(),11)+cents(t.amountUsd(),11)+cents(t.aprPct(),5)+cents(t.annualFeeUsd(),9)+digits(t.termMonths(),3)+cents(policy.minimumAprPct().get(offer.product()),5)+cents(policy.maximumAprReductionPct(),5)+digits(policy.minimumTermMonths(),3)+digits(policy.maximumTermMonths(),3)+digits(policy.maximumTermChangeMonths(),3)+digits(policy.maximumAmountChangePct(),3);
        try{var p=new ProcessBuilder(binary.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();try(var out=p.getOutputStream()){out.write((record+"\n").getBytes(StandardCharsets.US_ASCII));}if(!p.waitFor(10,TimeUnit.SECONDS)){p.destroyForcibly();throw new IllegalStateException("Offer COBOL policy timed out");}String result=new String(p.getInputStream().readNBytes(128),StandardCharsets.US_ASCII).trim();if(p.exitValue()!=0||!Set.of("APPROVED","AMOUNT_OUTSIDE_ENVELOPE","APR_OUTSIDE_ENVELOPE","FEE_OUTSIDE_ENVELOPE","CARD_TERM_MUST_BE_ZERO","TERM_OUTSIDE_ENVELOPE").contains(result))throw new IllegalStateException("Invalid COBOL offer response");return result;}catch(Exception e){throw new IllegalStateException("Offer COBOL policy unavailable",e);}
    }
    /** Formats a numeric field to the required fixed width for COBOL transport. */
    private static String digits(long n,int width){String s=Long.toString(n);if(n<0||s.length()>width)throw new Problem(422,"Offer term outside numeric contract");return "0".repeat(width-s.length())+s;}
    /** Converts a monetary amount to integer cents for fixed-width transport. */
    private static String cents(BigDecimal n,int width){return digits(n.movePointRight(2).longValueExact(),width);}
    /** Converts or validates monetary values at the precision required by this boundary. */
    private static void money(BigDecimal n,long max){if(n==null||n.signum()<0||n.compareTo(BigDecimal.valueOf(max))>0||n.stripTrailingZeros().scale()>2)throw new Problem(422,"Invalid offer monetary value");}
    /** Validates the exact-term variant request and its required source references. */
    private static void validate(Terms t){if(t==null)throw new Problem(422,"Terms required");money(t.amountUsd(),100000000);money(t.aprPct(),100);money(t.annualFeeUsd(),1000000);if(t.amountUsd().signum()==0||t.termMonths()<0||t.termMonths()>120)throw new Problem(422,"Invalid amount or term");}
}

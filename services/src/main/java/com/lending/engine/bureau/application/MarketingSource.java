/**
 * Step 2 boundary rechecks current source decisions, offer-scoped rules and mandatory consent/suppression
 * before bureau admission or presentation.
 */
package com.lending.engine.bureau.application;

import com.lending.engine.application.Ports.*;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.bureau.domain.Bureau;
import com.lending.engine.domain.Model.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/**
 * Step 2 boundary rechecks current source decisions, offer-scoped rules and mandatory consent/suppression
 * before bureau admission or presentation.
 */
public final class MarketingSource implements BureauPorts.Source, com.lending.engine.response.application.ResponsePorts.Source {
    private final Store store;private final Clock clock;
    /** Initializes marketing source with the supplied configuration and dependencies. */
    public MarketingSource(Store store,Clock clock){this.store=store;this.clock=clock;}
    private com.lending.engine.simulation.application.PublishedPolicies published;
    /** Connects the published offer-rule evaluator to the existing qualification workflow. */
    public void published(com.lending.engine.simulation.application.PublishedPolicies policy){published=policy;}
    /** Resolves a finalized qualification and rechecks its current customer, offer and campaign evidence. */
    public Snapshot current(Reference ref){return withCurrent(ref,Function.identity());}
    /** Returns pre-screen catalog matches with their current qualification state. */
    public Object prescreens(String after,int limit){
        if(limit<1||limit>100)throw new Problem(422,"limit must be 1-100");String runId="";int ordinal=-1;
        if(after!=null&&!after.isEmpty())try{String cursor=new String(Base64.getUrlDecoder().decode(after),java.nio.charset.StandardCharsets.UTF_8);String[] parts=cursor.split(":");runId=parts[0];Bureau.id(runId,"runId");ordinal=Integer.parseInt(parts[1]);if(parts.length!=2||ordinal<0)throw new IllegalArgumentException();}catch(Exception e){throw new Problem(422,"Invalid pre-screen cursor");}
        final String start=runId;final int position=ordinal;
        return store.read(s->{var scanned=s.qualificationScan(start,position,limit);var rows=new ArrayList<Object>();
            for(var row:scanned){var run=s.marketingRun(row.runId());var q=row.qualification();if(run.finalizedAt()==null||!q.outcome().equals("QUALIFIED"))continue;var ref=new Reference(run.id(),q.riskId(),q.id());var evidence=withEvidence(ref,e->e);var profile=s.customer(q.customerId()).profiles().stream().filter(p->p.version()==q.profileVersion()).findFirst().orElseThrow();var data=profile.data();var offer=evidence.offer().data();var amount=switch(q.product()){case PERSONAL_LOAN->data.personalLoanAmountUsd();case CREDIT_CARD->data.requestedCardLimitUsd();case AUTO_LOAN->data.autoLoanAmountUsd();};
                var item=new LinkedHashMap<String,Object>();item.put("id",com.lending.engine.response.infrastructure.ResponseRepository.id(ref));item.put("customerId",q.customerId());item.put("source",ref);item.put("qualification",q);item.put("terms",new com.lending.engine.offers.OfferSource.Terms(amount,offer.illustrativeAprPct(),offer.annualFeeUsd(),offer.termMonths()));item.put("validUntil",evidence.validUntil());item.put("status",evidence.failure()==null?"AWAITING_BUREAU":"WITHDRAWN");rows.add(item);
            }
            String next=null;if(scanned.size()==limit){var last=scanned.get(scanned.size()-1);next=Base64.getUrlEncoder().withoutPadding().encodeToString((last.runId()+":"+last.ordinal()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}var page=new LinkedHashMap<String,Object>();page.put("items",rows);page.put("nextAfter",next);return page;
        });
    }
    /** Runs an action while the referenced qualification remains protected by current source validation. */
    public <T>T withCurrent(Reference ref,Function<Snapshot,T> action){Bureau.reference(ref);return store.read(s->action.apply(validate(s,ref)));}
    /** Runs an action with current source evidence under the source-store transaction boundary. */
    public <T>T withEvidence(Reference ref,Function<com.lending.engine.response.domain.DecisionResponse.Evidence,T> action){
        Bureau.reference(ref);return store.read(s->{
            var run=s.marketingRun(ref.runId());if(run==null)throw new Problem(404,"Marketing run not found");
            var q=s.qualificationsForRisk(ref.runId(),ref.riskId()).stream().filter(x->x.id().equals(ref.qualificationId())).findFirst().orElseThrow(()->new Problem(404,"Qualification not found"));
            var offer=run.offers().stream().filter(o->o.id().equals(q.offerId())&&o.version()==q.offerVersion()).findFirst().orElseThrow(()->new Problem(409,"Qualified offer snapshot missing"));
            var campaign=run.campaigns().stream().filter(c->c.id().equals(q.campaignId())&&c.version()==q.campaignVersion()).findFirst().orElseThrow(()->new Problem(409,"Qualified campaign snapshot missing"));
            String failure=null;try{validate(s,ref);}catch(Problem e){if(e.status>=500)throw e;failure="STEP2_INVALID: "+e.getMessage();}

            if(failure==null&&s.suppressionHistory(q.customerId()).stream().anyMatch(x->x.version()>(q.suppressionVersion()==null?0:q.suppressionVersion())&&Boolean.TRUE.equals(x.data().active())&&(x.data().expiresOn()==null||!LocalDate.parse(x.data().expiresOn()).isBefore(Instant.parse(x.updatedAt()).atZone(ZoneOffset.UTC).toLocalDate()))))failure="SUPPRESSION_ADDED_SINCE_QUALIFICATION";
            Instant until=LocalDate.parse(offer.data().endsOn()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant campaignEnd=LocalDate.parse(campaign.data().endsOn()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();if(campaignEnd.isBefore(until))until=campaignEnd;
            var reservations=s.sourceReservations(ref.runId(),q.customerId(),q.campaignId());
            if(reservations.isEmpty())until=Instant.EPOCH;else for(var r:reservations){Instant end=Instant.parse(r.expiresAt());if(end.isBefore(until))until=end;}
            var customer=s.customer(q.customerId());if(customer!=null)for(var d:customer.decisions())if(d.id().equals(q.currentDecisionId())){Instant end=LocalDate.parse(d.validUntil()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();if(end.isBefore(until))until=end;}
            return action.apply(new com.lending.engine.response.domain.DecisionResponse.Evidence(q,offer,until.toString(),failure));
        });
    }
    /** Reads a bounded page of finalized qualifications for bureau batch expansion. */
    public List<SourceRow> page(String runId,int after,int limit){
        Bureau.id(runId,"runId");if(after< -1||limit<1||limit>500)throw new Problem(422,"Invalid source page");
        return store.read(s->{var run=s.marketingRun(runId);if(run==null)throw new Problem(404,"Marketing run not found");
            if(!run.status().equals("FINALIZED"))throw new Problem(409,"Step 2 must be FINALIZED");return s.qualificationPage(runId,after,limit);});
    }
    /**
     * Checks that the finalized qualification remains current and permitted by mandatory gates. Later
     * suppression removal cannot revive a qualification invalidated by an intervening suppression
     * revision.
     */
    private Snapshot validate(Session s,Reference ref){
        var run=s.marketingRun(ref.runId());if(run==null)throw new Problem(404,"Marketing run not found");
        if(!run.status().equals("FINALIZED"))throw new Problem(409,"Step 2 must be FINALIZED and not cancelled");
        var q=s.qualificationsForRisk(ref.runId(),ref.riskId()).stream().filter(x->x.id().equals(ref.qualificationId())).findFirst().orElseThrow(()->new Problem(404,"Qualification not found for this Risk ID"));
        if(!q.outcome().equals("QUALIFIED"))throw new Problem(409,"Step 2 qualification is excluded");
        var customer=s.customer(q.customerId());var now=clock.instant();var today=LocalDate.ofInstant(now,ZoneOffset.UTC);
        if(customer==null||customer.current().version()!=q.profileVersion())throw new Problem(409,"Customer profile changed; finalize a new Step 2 run");
        var decision=customer.latest(q.product());var data=customer.current().data();
        var qualifiedOffer=s.offer(q.offerId());boolean eligible=decision!=null&&decision.status()==Status.ELIGIBLE;
        if(qualifiedOffer!=null&&qualifiedOffer.rules()!=null){if(published==null)throw new Problem(503,"Published rules unavailable");eligible=published.underwriting(customer,qualifiedOffer).eligible()&&published.marketing(customer,qualifiedOffer,false).eligible();}
        if(decision==null||!Objects.equals(decision.id(),q.currentDecisionId())||!eligible||LocalDate.parse(decision.validUntil()).isBefore(today))throw new Problem(409,"Upstream underwriting is stale or ineligible");
        if(!Boolean.TRUE.equals(data.marketingOptIn())||!Boolean.FALSE.equals(data.prescreenOptOut()))throw new Problem(409,"Customer consent no longer qualifies");
        var suppression=s.suppression(customer.id());
        if(suppression!=null&&Boolean.TRUE.equals(suppression.data().active())&&(suppression.data().expiresOn()==null||!LocalDate.parse(suppression.data().expiresOn()).isBefore(today)))throw new Problem(409,"Customer is globally suppressed");
        if(s.sourceReservations(ref.runId(),customer.id(),q.campaignId()).stream().noneMatch(r->r.releasedAt()==null&&Instant.parse(r.expiresAt()).isAfter(now)))throw new Problem(409,"Marketing reservation expired or was released");
        var offer=s.offer(q.offerId());var campaign=s.campaign(q.campaignId());
        if(offer==null||campaign==null||offer.version()!=q.offerVersion()||campaign.version()!=q.campaignVersion()||!Boolean.TRUE.equals(offer.data().active())||!Boolean.TRUE.equals(campaign.data().active())||today.isBefore(LocalDate.parse(offer.data().startsOn()))||today.isAfter(LocalDate.parse(offer.data().endsOn()))||today.isBefore(LocalDate.parse(campaign.data().startsOn()))||today.isAfter(LocalDate.parse(campaign.data().endsOn())))throw new Problem(409,"Campaign or offer changed/expired; finalize a new run");
        return new Snapshot(ref,q,customer.current(),offer);
    }
}

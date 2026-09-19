/**
 * Bureau orchestration carries frozen offer rules over the credit API and shares durable
 * interactive/batch/event admission; failures never imply a decline.
 */
package com.lending.engine.bureau.application;

import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.bureau.infrastructure.BureauQueue;
import com.lending.engine.domain.Model.Problem;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Bureau orchestration carries frozen offer rules over the credit API and shares durable
 * interactive/batch/event admission; failures never imply a decline.
 */
public final class BureauEngine implements AutoCloseable {
    public final BureauQueue queue;public final BureauPorts.Source source;public final BureauPorts.CreditGateway credit;
    private final ScheduledExecutorService workers;private final Clock clock;
    /** Initializes bureau engine with the supplied configuration and dependencies. */
    public BureauEngine(BureauQueue queue,BureauPorts.Source source,BureauPorts.CreditGateway credit,Clock clock,int concurrency){
        this.queue=queue;this.source=source;this.credit=credit;this.clock=clock;if(concurrency<1||concurrency>64)throw new IllegalArgumentException("bureau.workers must be 1-64");workers=Executors.newScheduledThreadPool(concurrency+1);
    }
    /** Admits a finalized marketing qualification as an idempotent bureau request. */
    public Map<String,Object> submit(Submit request){
        com.lending.engine.bureau.domain.Bureau.id(request.requestId(),"requestId");com.lending.engine.bureau.domain.Bureau.reference(request.source());
        try{var existing=queue.get(request.requestId());return queue.submit(request);}catch(Problem e){if(e.status!=404)throw e;}
        return source.withCurrent(request.source(),snapshot->queue.submit(request));
    }
    /** Admits a replayable batch of finalized marketing runs for bounded expansion. */
    public Map<String,Object> batch(BatchRequest request){
        if(request.runIds()==null||request.runIds().isEmpty()||request.runIds().size()>1000)throw new Problem(422,"Supply 1-1000 finalized run IDs");
        try{queue.batchStatus(request.requestId());return queue.batch(request);}catch(Problem e){if(e.status!=404)throw e;}
        for(String run:request.runIds())source.page(run,-1,1);return queue.batch(request);
    }
    /** Starts leased bureau workers and batch expansion. */
    public void start(int concurrency){workers.scheduleWithFixedDelay(()->safe(()->queue.expand(source)),0,100,TimeUnit.MILLISECONDS);
        for(int i=0;i<concurrency;i++)workers.scheduleWithFixedDelay(()->safe(this::processOne),0,20,TimeUnit.MILLISECONDS);}
    /** Runs a background operation while recording failures without terminating the scheduler. */
    private void safe(Runnable task){try{task.run();}catch(Exception e){System.err.println("Bureau worker paused: "+e.getClass().getSimpleName());}}
    /** Revalidates a leased source, calls the credit service and commits the fenced decision result. */
    public void processOne(){var work=queue.claim();if(work==null)return;var ref=new Reference(work.runId(),work.riskId(),work.qualificationId());
        try{
            if(work.attempts()>8){queue.finish(work,new Result(work.id(),work.riskId(),ref,"FAILED",null,"RETRY_BUDGET_EXHAUSTED",clock.instant().toString()));return;}
            Snapshot before=source.current(ref);var subjectId=credit.subjectId(before.customer().customerId());var data=before.customer().data();var terms=before.offer().data();
            var amount=switch(before.qualification().product()){case PERSONAL_LOAN->data.personalLoanAmountUsd();case CREDIT_CARD->data.requestedCardLimitUsd();case AUTO_LOAN->data.autoLoanAmountUsd();};
            var request=new CreditInput(work.id(),subjectId,before.customer().customerId(),before.qualification().product(),amount,data.monthlyIncomeUsd(),before.customer().creditInformationSource(),data.vehicleValueUsd(),terms.termMonths(),terms.illustrativeAprPct(),before.offer().rules());
            Assessment assessment=credit.assess(request);
            source.withCurrent(ref,current->{if(!current.equals(before))throw new Problem(409,"Source changed during bureau assessment");
                if(!Instant.parse(assessment.validUntil()).isAfter(clock.instant()))throw new Problem(409,"Bureau decision expired before completion");
                queue.finish(work,new Result(work.id(),work.riskId(),ref,"COMPLETED",assessment,null,clock.instant().toString()));return null;});
        }catch(Problem e){if(e.status>=500){queue.retry(work);return;}queue.finish(work,new Result(work.id(),work.riskId(),ref,"INVALIDATED",null,e.getMessage(),clock.instant().toString()));}
        catch(Exception e){queue.retry(work);}
    }
    /** Releases the resources owned by this component. */
    public void close(){workers.shutdownNow();try{workers.awaitTermination(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}

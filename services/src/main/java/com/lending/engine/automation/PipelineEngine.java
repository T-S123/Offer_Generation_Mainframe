/**
 * Durable automatic Steps 1-3 coordination; response, personalization and execution workers continue
 * asynchronously.
 */
package com.lending.engine.automation;
import com.lending.engine.application.Ports.Store;
import com.lending.engine.domain.Model.*;
import com.lending.engine.marketing.application.MarketingEngine;
import com.lending.engine.bureau.application.BureauEngine;
import com.lending.engine.bureau.domain.Bureau.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Durable automatic Steps 1-3 coordination; response, personalization and execution workers continue
 * asynchronously.
 */
public final class PipelineEngine implements AutoCloseable {
    private final Store store;private final MarketingEngine marketing;private final BureauEngine bureau;private final Clock clock;private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();private volatile String health="STARTING";
    /** Initializes pipeline engine with the supplied configuration and dependencies. */
    public PipelineEngine(Store store,MarketingEngine marketing,BureauEngine bureau,Clock clock){this.store=store;this.marketing=marketing;this.bureau=bureau;this.clock=clock;}
    /** Starts periodic processing of durable customer pipeline admissions. */
    public void start(){worker.scheduleWithFixedDelay(()->{try{tick();health="RUNNING";}catch(Exception e){health="RETRYING";}},0,100,TimeUnit.MILLISECONDS);}
    /** Processes a bounded batch of customer pipelines that are due for advancement. */
    public synchronized void tick(){for(var item:store.read(s->s.pendingPipelines(clock.millis(),20)))process(item);}
    /** Persists pipeline progress only while its source token still matches. */
    private void save(Pipeline p,String state,String run,String detail,long delay){store.write(s->{var current=s.pipeline(p.customerId());if(current!=null&&current.state().equals("COMPLETED")&&Objects.equals(current.runId(),run)&&current.token().equals(p.token()))return null;s.savePipeline(new Pipeline(p.customerId(),p.token(),state,run,clock.instant().toString(),detail),clock.millis()+delay);return null;});}
    /**
     * Advances a current customer profile through population creation, marketing qualification and bureau
     * submission.
     */
    private void process(Pipeline item){String runId=item.runId();try{var run=runId==null?marketing.automatic(item.customerId(),item.token()):marketing.run(runId);runId=run.id();if(!run.status().equals("FINALIZED")){save(item,"STOPPED",runId,"Marketing run was cancelled",0);return;}
        var qualified=new ArrayList<com.lending.engine.marketing.domain.Marketing.Qualification>();int offset=0;while(true){var page=marketing.results(run.id(),offset,200,null);qualified.addAll(page.items().stream().filter(q->q.outcome().equals("QUALIFIED")).toList());offset+=page.items().size();if(offset>=page.total())break;}
        if(qualified.isEmpty()){save(item,"NO_ELIGIBLE_OFFERS",runId,"No Step 2 matches; review underwriting, consent and qualification reasons",0);return;}
        save(item,"BUREAU_PENDING",runId,"Bureau requests admitted automatically",500);boolean pending=false,approved=false,failed=false;
        for(var q:qualified){var current=store.read(s->s.pipeline(item.customerId()));if(!current.token().equals(item.token()))return;var ref=new Reference(run.id(),q.riskId(),q.id());String request="AUTO-B-"+q.id();bureau.submit(new Submit(request,ref));var result=(Result)bureau.queue.get(request).get("result");if(result==null)pending=true;else if(result.status().equals("FAILED"))failed=true;else if(result.decision()!=null&&result.decision().outcome().equals("APPROVED"))approved=true;}
        if(!pending)save(item,approved?"OFFERS_PENDING":failed?"ATTENTION_REQUIRED":"NO_ELIGIBLE_OFFERS",runId,approved?"Bureau approved; offer creation and campaign execution continue automatically":failed?"Bureau technical retry budget exhausted; inspect bureau work before retrying":"Bureau processing completed without an approved offer",0);
    }catch(Problem e){save(item,e.status>=500?"RETRYING":"STOPPED",runId,e.status>=500?"Dependency unavailable; retrying automatically":e.getMessage(),2000);}catch(Exception e){save(item,"RETRYING",runId,"Dependency unavailable; retrying automatically",2000);}}
    /** Reports component progress and availability for operational health checks. */
    public String health(){return health;}
    /** Releases the resources owned by this component. */
    public void close(){worker.shutdownNow();try{worker.awaitTermination(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}

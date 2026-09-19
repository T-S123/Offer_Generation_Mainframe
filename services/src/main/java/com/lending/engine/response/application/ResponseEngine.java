/**
 * Restartable bounded projection and eligibility reconciliation; customer reads fail closed and replay
 * always uses the latest revalidated revision.
 */
package com.lending.engine.response.application;

import com.lending.engine.bureau.domain.Bureau;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.response.domain.DecisionResponse.*;
import com.lending.engine.response.infrastructure.ResponseRepository;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/**
 * Restartable bounded projection and eligibility reconciliation; customer reads fail closed and replay
 * always uses the latest revalidated revision.
 */
public final class ResponseEngine implements AutoCloseable {
    public final ResponseRepository repository;private final ResponsePorts.Source source;private final Clock clock;
    private final ScheduledExecutorService workers=Executors.newScheduledThreadPool(2);
    private final int batchSize=Integer.getInteger("response.batch.size",100),pollMillis=Integer.getInteger("response.poll.ms",250);
    private volatile String projection="STARTING",reconciliation="STARTING";
    /** Initializes response engine with the supplied configuration and dependencies. */
    public ResponseEngine(ResponseRepository repository,ResponsePorts.Source source,Clock clock){this.repository=repository;this.source=source;this.clock=clock;if(batchSize<1||batchSize>1000||pollMillis<50||pollMillis>60000)throw new IllegalArgumentException("response.batch.size must be 1-1000 and response.poll.ms 50-60000");}
    /** Starts bounded bureau-result projection and automatic eligibility reconciliation. */
    public void start(){workers.scheduleWithFixedDelay(()->{try{projectPending();projection="RUNNING";}catch(Exception e){projection="RETRYING";}},0,pollMillis,TimeUnit.MILLISECONDS);workers.scheduleWithFixedDelay(()->{try{reconcile();reconciliation="RUNNING";}catch(Exception e){reconciliation="RETRYING";}},0,pollMillis,TimeUnit.MILLISECONDS);}
    /** Projects a bounded page of completed bureau results into decision responses. */
    public void projectPending(){for(var work:repository.pending(batchSize))try{project(work);}catch(Exception e){repository.failed(work.requestId());}}
    /** Projects a completed bureau request using current protected Step 2 evidence. */
    private void project(ResponseRepository.Pending work){source.withEvidence(work.result().source(),e->{repository.project(work,e,clock.instant());return null;});}
    /** Refreshes due response eligibility and publishes revisions when the current state changes. */
    public void reconcile(){RuntimeException failure=null;for(String id:repository.due(clock.instant(),batchSize))try{get(id);}catch(RuntimeException e){repository.defer(id,clock.instant());failure=e;}if(failure!=null)throw failure;}
    /**
     * Returns a revalidated response after checking for newer completed bureau work, even when background
     * projection is behind.
     */
    public Response get(String id){Bureau.id(id,"responseId");var prior=repository.get(id);

        var latest=repository.latestUnprojected(prior.source());if(latest!=null)project(latest);
        return source.withEvidence(prior.source(),e->repository.revalidate(id,e,clock.instant()));
    }
    /** Retrieves a bounded page of responses with current eligibility checks. */
    public Page<Response> list(String customer,String after,int limit){limit(limit);if(customer!=null)Bureau.id(customer,"customerId");if(after!=null&&!after.isEmpty())Bureau.id(after,"after");
        var ids=repository.ids(customer,after,limit);var rows=new ArrayList<Response>();for(String id:ids){var value=get(id);if(customer==null||value.marketingEligible())rows.add(value);}
        return new Page<>(rows,ids.size()==limit?ids.get(ids.size()-1):null);
    }
    /** Returns the immutable revisions of a qualification response. */
    public List<Map<String,Object>> history(String id,long after,int limit){Bureau.id(id,"responseId");limit(limit);if(after<0)throw new Problem(422,"after must be non-negative");return repository.history(id,after,limit);}
    /** Queues the latest revalidated response revision for downstream delivery. */
    public Map<String,Object> replay(String id){get(id);return repository.replay(id);}
    /** Reports component progress and availability for operational health checks. */
    public Map<String,Object> health(){var h=repository.health();h.put("projector",projection);h.put("reconciler",reconciliation);return h;}
    /** Enforces the supported page-size bounds before querying stored records. */
    private static void limit(int value){if(value<1||value>100)throw new Problem(422,"limit must be 1-100");}
    /** Releases the resources owned by this component. */
    public void close(){workers.shutdownNow();try{workers.awaitTermination(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}

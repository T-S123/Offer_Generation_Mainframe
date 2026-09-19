/**
 * Marketing offer repository: hidden pre-screen recovery, fresh snapshots and complete current campaign
 * candidates for execution.
 */
package com.lending.offers.application;
import com.lending.offers.infrastructure.*;
import static com.lending.offers.infrastructure.Database.*;
import com.lending.offers.Json;
import java.util.*;
import java.util.concurrent.*;

/**
 * Marketing offer repository: hidden pre-screen recovery, fresh snapshots and complete current campaign
 * candidates for execution.
 */
public final class StorageEngine implements AutoCloseable {
    public final StorageJournal journal;private final Source source;private final OfferEngine offers;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();private volatile String state="STARTING";
    /** Initializes storage engine with the supplied configuration and dependencies. */
    public StorageEngine(OfferEngine offers,Source source){this.offers=offers;this.source=source;this.journal=new StorageJournal(offers.store.db);}
    /** Starts recovery of finalized pre-screen catalog matches. */
    public void start(){worker.scheduleWithFixedDelay(()->{try{scan();state="RUNNING";}catch(Exception e){state="RETRYING";}},0,500,TimeUnit.MILLISECONDS);}
    /** Imports a bounded source page and persists progress for pre-screen recovery. */
    public void scan(){String after=journal.db.tx(c->scalar(c,"SELECT value FROM state WHERE key='prescreen'"));var page=source.get("prescreen?limit=100"+(after==null||after.isEmpty()?"":"&after="+after));for(var row:page.path("items"))journal.prescreen(row);String next=page.path("nextAfter").isNull()?"":page.path("nextAfter").asText();journal.db.tx(c->{execute(c,"INSERT INTO state VALUES('prescreen',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",next);return null;});}
    /** Loads the latest stored offer and rechecks any current customer-visible choices. */
    public Object current(String id){offers.current(id);return journal.latest(id);}
    /**
     * Returns all current qualified choices for a customer and campaign, including pending creation
     * status.
     */
    public Object candidates(String customer,String campaign){if(customer==null||campaign==null||!customer.matches("[A-Za-z0-9_.:-]{1,80}")||!campaign.matches("[A-Za-z0-9_.:-]{1,80}"))throw new Json.Fault(422,"Customer and campaign IDs required");var ids=journal.db.tx(c->{var rows=new ArrayList<String>();try(var p=statement(c,"SELECT id FROM offers WHERE customer_id=? AND (payload::jsonb->>'campaignId')=? AND status='ACTIVE' ORDER BY id LIMIT 1001",customer,campaign);var r=p.executeQuery()){while(r.next())rows.add(r.getString(1));}return rows;});if(ids.size()>1000)throw new Json.Fault(422,"Campaign candidate limit exceeded; no partial package generated");var rows=new ArrayList<Object>();boolean pending=false;for(String id:ids)try{var offer=offers.current(id);if(Set.of("WAITING_FOR_MODEL","PERSONALIZATION_PENDING").contains(offer.personalizationStatus()))pending=true;rows.add(Map.of("offer",offer,"stored",journal.latest(id).offer()));}catch(Json.Fault e){if(e.status!=404&&e.status!=409)throw e;}return Map.of("items",rows,"pending",pending);}
    /** Reports component progress and availability for operational health checks. */
    public Map<String,Object> health(){var value=new LinkedHashMap<>(journal.health());value.put("prescreenScanner",state);return value;}
    /** Releases the resources owned by this component. */
    public void close(){worker.shutdownNow();try{worker.awaitTermination(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}

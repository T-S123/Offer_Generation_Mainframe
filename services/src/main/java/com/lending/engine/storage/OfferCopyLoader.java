/**
 * Independent Kafka consumer and ordered HTTP catch-up for one copy schema; both paths deduplicate before
 * checkpointing.
 */
package com.lending.engine.storage;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.infrastructure.Json;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;

/**
 * Independent Kafka consumer and ordered HTTP catch-up for one copy schema; both paths deduplicate before
 * checkpointing.
 */
public final class OfferCopyLoader implements AutoCloseable {
    public final OfferCopyStore store;private final StorageClient source;private final Properties properties=new Properties();private final String topic;private final ExecutorService consumerThread=Executors.newSingleThreadExecutor();private final ScheduledExecutorService recovery=Executors.newSingleThreadScheduledExecutor();private volatile KafkaConsumer<String,String> consumer;private volatile boolean running=true;private volatile String kafka="STARTING",http="STARTING";
    /** Initializes offer copy loader with the supplied configuration and dependencies. */
    public OfferCopyLoader(OfferCopyStore store,StorageClient source,String bootstrap,String topic){this.store=store;this.source=source;this.topic=topic;properties.put("bootstrap.servers",bootstrap);properties.put("group.id",store.schema+"-storage-v1");properties.put("enable.auto.commit","false");properties.put("auto.offset.reset","earliest");properties.put("max.poll.records","100");properties.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");properties.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");}
    /** Validates and durably stores a repository event before Kafka acknowledgement. */
    public void accept(String key,String payload,String location){try{var event=Json.MAPPER.readTree(payload);StorageContract.validate(event);if(!event.path("offer").path("id").asText().equals(key))throw new Problem(422,"Wrong storage partition key");store.receive(event);}catch(com.fasterxml.jackson.core.JsonProcessingException|Problem|IllegalArgumentException e){store.reject(location,payload==null?"null":payload);}}
    /**
     * Replays missed repository events over HTTP and advances the durable cursor after each committed
     * page.
     */
    public synchronized void catchUp(){long cursor=store.cursor();var page=source.get("events?after="+cursor+"&limit=100");long last=cursor;for(var event:page.path("items")){long sequence=event.path("sequence").asLong();if(sequence<=last)throw new Problem(503,"Non-monotonic recovery page");store.receive(event);last=sequence;}if(last>cursor)store.cursor(last);}
    /** Starts independent Kafka ingestion and ordered HTTP recovery for one copy schema. */
    public void start(){recovery.scheduleWithFixedDelay(()->{try{catchUp();http="RUNNING";}catch(Exception e){http="RETRYING";}},0,500,TimeUnit.MILLISECONDS);consumerThread.submit(()->{while(running){try(var c=new KafkaConsumer<String,String>(properties)){consumer=c;c.subscribe(List.of(topic));while(running){for(var r:c.poll(Duration.ofMillis(500))){accept(r.key(),r.value(),r.topic()+":"+r.partition()+":"+r.offset());c.commitSync(Map.of(new TopicPartition(r.topic(),r.partition()),new OffsetAndMetadata(r.offset()+1)));}kafka=c.assignment().isEmpty()?"CONNECTING":"RUNNING";}}catch(Exception e){kafka="RETRYING";if(running)try{Thread.sleep(1000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();break;}}}});}
    /** Reports component progress and availability for operational health checks. */
    public Map<String,Object> health(){var h=store.health();h.put("kafka",kafka);h.put("httpRecovery",http);return h;}
    /** Releases the resources owned by this component. */
    public void close(){running=false;var c=consumer;if(c!=null)c.wakeup();recovery.shutdownNow();consumerThread.shutdown();try{recovery.awaitTermination(12,TimeUnit.SECONDS);consumerThread.awaitTermination(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}store.close();}
}

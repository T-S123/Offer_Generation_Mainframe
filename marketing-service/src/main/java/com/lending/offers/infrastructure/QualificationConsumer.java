/**
 * Kafka ingestion acknowledges durable inbox/quarantine commits and reports RUNNING only after partition
 * assignment; business workers run separately.
 */
package com.lending.offers.infrastructure;
import com.lending.offers.Json;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Kafka ingestion acknowledges durable inbox/quarantine commits and reports RUNNING only after partition
 * assignment; business workers run separately.
 */
public final class QualificationConsumer implements AutoCloseable {
    private final OfferStore store;private final Properties config=new Properties();private final String topic;private volatile boolean running=true;private volatile KafkaConsumer<String,String> consumer;private final ExecutorService thread=Executors.newSingleThreadExecutor();private volatile String status="STARTING";
    /** Initializes qualification consumer with the supplied configuration and dependencies. */
    public QualificationConsumer(OfferStore store,String bootstrap,String group,String topic){this.store=store;this.topic=topic;config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap);config.put(ConsumerConfig.GROUP_ID_CONFIG,group);config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,"false");config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,"100");config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,"300000");}
    /** Commits or quarantines a qualification event before its Kafka offset can advance. */
    public void accept(String key,String payload,String position){try{var event=Json.tree(payload);if(event.path("schemaVersion").asInt()!=1||!event.path("type").asText().equals("QUALIFICATION_UPDATED")||!event.path("eventId").asText().matches("[A-Za-z0-9_.:-]{1,160}")||!event.path("response").path("source").path("riskId").asText().equals(key))throw new Json.Fault(422,"Invalid event envelope/key");store.receive(event.path("eventId").asText(),event.path("response"));}catch(Json.Fault e){store.reject(position,payload,e.getMessage());}}
    /** Starts the Kafka qualification consumer and tracks partition assignment. */
    public void start(){thread.submit(()->{while(running){try(var c=new KafkaConsumer<String,String>(config)){consumer=c;c.subscribe(List.of(topic));while(running){var records=c.poll(Duration.ofMillis(500));for(var r:records){accept(r.key(),r.value(),r.topic()+":"+r.partition()+":"+r.offset());c.commitSync(Map.of(new TopicPartition(r.topic(),r.partition()),new OffsetAndMetadata(r.offset()+1)));}status=c.assignment().isEmpty()?"CONNECTING":"RUNNING";}}catch(Exception e){status="RETRYING";if(running)try{Thread.sleep(1000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();break;}}}status="STOPPED";});}
    /** Returns the current processing status for operational monitoring. */
    public String status(){return status;}
    /** Releases the resources owned by this component. */
    public void close(){running=false;var c=consumer;if(c!=null)c.wakeup();thread.shutdown();try{if(!thread.awaitTermination(10,TimeUnit.SECONDS))thread.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}

/**
 * Kafka inbox/outbox adapter with durable delivery attempts and optional downstream topics; consumers
 * deduplicate stable event IDs and compare response versions.
 */
package com.lending.engine.bureau.infrastructure;

import static com.lending.engine.bureau.infrastructure.BureauDatabase.*;
import com.lending.engine.bureau.application.BureauEngine;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.bureau.domain.Bureau;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.infrastructure.Json;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Kafka inbox/outbox adapter with durable delivery attempts and optional downstream topics; consumers
 * deduplicate stable event IDs and compare response versions.
 */
public final class KafkaTransport implements AutoCloseable {
    /** Carries event data for kafka transport operations. */
    public record Event(String eventId,Submit request) {}
    private final BureauEngine engine;private final String bootstrap;private volatile boolean closed;private volatile KafkaConsumer<String,String> consumer;
    private volatile boolean topicsReady;
    private final List<String> outputTopics;
    private final ExecutorService threads=Executors.newFixedThreadPool(2);private volatile String producerStatus="STARTING",consumerStatus="STARTING";
    /** Initializes kafka transport with the supplied configuration and dependencies. */
    public KafkaTransport(BureauEngine engine,String bootstrap){this(engine,bootstrap,List.of());}
    /** Initializes kafka transport with the supplied configuration and dependencies. */
    public KafkaTransport(BureauEngine engine,String bootstrap,List<String> outputTopics){this.engine=engine;this.bootstrap=bootstrap;this.outputTopics=List.copyOf(outputTopics);}
    /** Returns the current processing status for operational monitoring. */
    public String status(){return "consumer="+consumerStatus+"; publisher="+producerStatus;}
    /** Builds Kafka client properties for the configured local broker. */
    private Properties props(){var p=new Properties();p.put("bootstrap.servers",bootstrap);p.put("request.timeout.ms","10000");p.put("default.api.timeout.ms","15000");return p;}
    /** Starts the Kafka producer, request consumer and durable outbox relay. */
    public void start(){threads.submit(this::publish);threads.submit(this::consume);}
    /** Creates the required Kafka topics when they do not already exist. */
    private void topics()throws Exception {if(topicsReady)return;try(var admin=Admin.create(props())){var names=admin.listTopics().names().get(15,TimeUnit.SECONDS);var missing=new ArrayList<NewTopic>();var desired=new LinkedHashSet<>(List.of(engine.queue.inputTopic,engine.queue.resultsTopic,engine.queue.dlqTopic));desired.addAll(outputTopics);for(String name:desired)if(!names.contains(name))missing.add(new NewTopic(name,12,(short)1));if(!missing.isEmpty())try{admin.createTopics(missing).all().get(15,TimeUnit.SECONDS);}catch(ExecutionException e){if(!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException))throw e;}}topicsReady=true;}
    /** Relays pending outbox records to Kafka and marks only acknowledged records as delivered. */
    private void publish(){var p=props();p.put("key.serializer","org.apache.kafka.common.serialization.StringSerializer");p.put("value.serializer","org.apache.kafka.common.serialization.StringSerializer");p.put("enable.idempotence","true");p.put("acks","all");p.put("delivery.timeout.ms","20000");p.put("max.block.ms","10000");
        try(var producer=new KafkaProducer<String,String>(p)){while(!closed){try{topics();int delivered=engine.queue.db.tx(c->{try(var s=statement(c,"SELECT id,topic,event_key,payload FROM bureau_outbox WHERE sent_at IS NULL ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");var r=s.executeQuery()){
            if(!r.next())return 0;String id=r.getString(1);execute(c,"UPDATE bureau_outbox SET attempts=attempts+1,last_attempt_at=now() WHERE id=?",id);
            try{producer.send(new ProducerRecord<>(r.getString(2),r.getString(3),r.getString(4))).get(22,TimeUnit.SECONDS);execute(c,"UPDATE bureau_outbox SET sent_at=now(),last_error=NULL WHERE id=?",id);return 1;}
            catch(Exception e){execute(c,"UPDATE bureau_outbox SET last_error=? WHERE id=?",e.getClass().getSimpleName(),id);return -1;}}});producerStatus=delivered<0?"RETRYING":"CONNECTED";if(delivered<=0)pause(delivered<0?2000:200);
        }catch(Exception e){producerStatus="RETRYING";pause(2000);}}}catch(Exception e){producerStatus="STOPPED";}}
    /** Consumes Kafka records and acknowledges them after durable handling. */
    private void consume(){while(!closed){try{topics();var p=props();p.put("group.id",engine.queue.inputTopic+".consumer");p.put("enable.auto.commit","false");p.put("auto.offset.reset","earliest");p.put("max.poll.records","50");p.put("max.partition.fetch.bytes","1048576");p.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");p.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
            try(var active=new KafkaConsumer<String,String>(p)){consumer=active;active.subscribe(List.of(engine.queue.inputTopic));consumerStatus="CONNECTED";
                while(!closed){for(var record:active.poll(Duration.ofMillis(500))){accept(record);active.commitSync(Map.of(new TopicPartition(record.topic(),record.partition()),new OffsetAndMetadata(record.offset()+1)));}}
            }
        }catch(WakeupException e){if(!closed)pause(1000);}catch(Exception e){consumerStatus="RETRYING";pause(2000);}finally{consumer=null;}}}
    /** Durably handles a request event or records its rejection before acknowledging the Kafka offset. */
    private void accept(ConsumerRecord<String,String> record){String coordinate=record.topic()+":"+record.partition()+":"+record.offset();String raw=record.value()==null?"null":record.value();
        Event event;
        try{if(raw.length()>1024*1024)throw new IllegalArgumentException();event=Json.read(raw,Event.class);Bureau.id(event.eventId(),"eventId");if(event.request()==null)throw new IllegalArgumentException();Bureau.id(event.request().requestId(),"requestId");Bureau.reference(event.request().source());}
        catch(Exception e){engine.queue.event(coordinate,raw,null,"MALFORMED_EVENT");return;}
        try{engine.source.withCurrent(event.request().source(),snapshot->{engine.queue.event(event.eventId(),raw,event.request(),null);return null;});}
        catch(Problem e){if(e.status>=500)throw e;engine.queue.event(coordinate,raw,null,"SOURCE_OR_IDEMPOTENCY_REJECTED: "+e.getMessage());}
    }
    /** Waits briefly between retries while preserving interruption. */
    private void pause(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    /** Releases the resources owned by this component. */
    public void close(){closed=true;var c=consumer;if(c!=null)c.wakeup();threads.shutdown();try{if(!threads.awaitTermination(25,TimeUnit.SECONDS))threads.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}

/**
 * Transactional outbox delivery: retry unacknowledged events, partition by offer family, never acknowledge
 * before Kafka accepts the record.
 */
package com.lending.offers.infrastructure;
import com.lending.offers.domain.StoredOffers.*;
import static com.lending.offers.infrastructure.Database.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.errors.TopicExistsException;

/**
 * Transactional outbox delivery: retry unacknowledged events, partition by offer family, never acknowledge
 * before Kafka accepts the record.
 */
public final class StoragePublisher implements AutoCloseable {
    private final Database db;private final String bootstrap,topic;private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();private KafkaProducer<String,String> producer;private volatile String status="STARTING";
    /** Initializes storage publisher with the supplied configuration and dependencies. */
    public StoragePublisher(Database db,String bootstrap,String topic){this.db=db;this.bootstrap=bootstrap;this.topic=topic;}
    /** Starts the durable storage-outbox publication loop. */
    public void start(){worker.scheduleWithFixedDelay(()->{try{if(producer==null)connect();status="RUNNING";for(int i=0;i<50&&publishOne();i++){}}catch(Exception e){status="RETRYING";}},0,500,TimeUnit.MILLISECONDS);}
    /** Creates the Kafka producer used to deliver queued storage events. */
    private void connect()throws Exception {try(var admin=Admin.create(Map.of("bootstrap.servers",bootstrap,"request.timeout.ms",5000,"default.api.timeout.ms",10000))){try{admin.createTopics(List.of(new NewTopic(topic,12,(short)1))).all().get(12,TimeUnit.SECONDS);}catch(ExecutionException e){if(!(e.getCause() instanceof TopicExistsException))throw e;}}Properties p=new Properties();p.put("bootstrap.servers",bootstrap);p.put("key.serializer","org.apache.kafka.common.serialization.StringSerializer");p.put("value.serializer","org.apache.kafka.common.serialization.StringSerializer");p.put("acks","all");p.put("enable.idempotence","true");p.put("delivery.timeout.ms","10000");p.put("request.timeout.ms","5000");p.put("max.block.ms","5000");producer=new KafkaProducer<>(p);}
    /** Publishes the next pending storage event and records acknowledgement only after delivery succeeds. */
    public boolean publishOne(){return db.tx(c->{try(var p=statement(c,"SELECT sequence,offer_id,payload FROM storage_events WHERE sent_at IS NULL ORDER BY sequence FOR UPDATE SKIP LOCKED LIMIT 1");var r=p.executeQuery()){if(!r.next())return false;long seq=r.getLong(1);try{producer.send(new ProducerRecord<>(topic,r.getString(2),r.getString(3))).get(15,TimeUnit.SECONDS);execute(c,"UPDATE storage_events SET sent_at=now(),attempts=attempts+1,last_error=NULL WHERE sequence=?",seq);}catch(Exception e){execute(c,"UPDATE storage_events SET attempts=attempts+1,last_error='Kafka delivery failed; retry pending' WHERE sequence=?",seq);status="RETRYING";return false;}return true;}});}
    /** Returns the current processing status for operational monitoring. */
    public String status(){return status;}
    /** Releases the resources owned by this component. */
    public void close(){worker.shutdownNow();try{worker.awaitTermination(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}if(producer!=null)producer.close(java.time.Duration.ofSeconds(5));}
}

/**
 * Independent marketing service with automatic local model training, pre-screen recovery and atomic
 * offer-storage publication.
 */
package com.lending.offers;
import com.lending.offers.Json;
import com.lending.offers.application.*;
import com.lending.offers.infrastructure.*;
import com.lending.offers.api.*;
import java.nio.file.*;

/**
 * Independent marketing service with automatic local model training, pre-screen recovery and atomic
 * offer-storage publication.
 */
public final class Main {
    /**
     * Loads local configuration, wires service dependencies and starts the selected application entry
     * point.
     */
    public static void main(String[] args)throws Exception {Path runtime=Path.of(System.getProperty("engine.home",".")).resolve("runtime");String token=Files.readString(runtime.resolve("marketing-api-token")).trim(),sourceToken=Files.readString(runtime.resolve("marketing-source-token")).trim();if(token.length()<32||sourceToken.length()<32)throw new IllegalStateException("Invalid local service credentials");
        var database=new Database(System.getenv("DATABASE_URL"),System.getProperty("marketing.db.schema","marketing_offers"));var store=new OfferStore(database);var engine=new OfferEngine(store,new HttpSource(Integer.getInteger("engine.api.port",8090),sourceToken));var kafka=new QualificationConsumer(store,System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS","127.0.0.1:9092"),System.getProperty("marketing.kafka.group","marketing-offers-v1"),System.getProperty("marketing.kafka.topic","marketing.qualification.updated.v1"));var server=new OfferServer(engine,kafka,Integer.getInteger("marketing.api.port",8092),token);
        var storage=new StorageEngine(engine,new HttpSource(Integer.getInteger("engine.api.port",8090),sourceToken));var publisher=new StoragePublisher(database,System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS","127.0.0.1:9092"),System.getProperty("storage.kafka.topic",com.lending.offers.domain.StoredOffers.TOPIC));server.storage(storage,publisher);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.close();kafka.close();storage.close();engine.close();publisher.close();database.close();}));server.start();if(Boolean.parseBoolean(System.getProperty("marketing.auto.train","true")))engine.automaticTraining();engine.start();storage.start();kafka.start();publisher.start();System.out.println("Local marketing offer service: 127.0.0.1:"+server.port());
    }
}

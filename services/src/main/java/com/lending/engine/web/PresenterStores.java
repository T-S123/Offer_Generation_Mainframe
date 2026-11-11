package com.lending.engine.web;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;

/** Offline maintenance for this local installation's owned projections, session offers, history and event streams.
 * The coordinator drains the application launchers before clearing and restarts them to seed defaults.
 * Local database checks and bounded Kafka admin requests fail before clearing if infrastructure is unavailable.
 */
public final class PresenterStores {
    private static final Set<String> TOPICS = Set.of("bureau.qualification.requested.v1",
        "bureau.qualification.completed.v1", "bureau.qualification.rejected.v1",
        "bureau.decision.published.v1", "marketing.qualification.updated.v1", "offers.storage.updated.v1");

    private static Map<String, Set<String>> owned() {
        var tables = new HashMap<String, Set<String>>();
        tables.put("public", Set.of("bureau_mapping","bureau_profiles","credit_assessments","bureau_batches",
            "bureau_work","bureau_batch_items","bureau_outbox","bureau_inbox","decision_responses",
            "decision_response_history","offer_variant_qualifications"));
        tables.put("marketing_offers", Set.of("inbox","rejected_events","offer_work","offers","offer_history",
            "selections","personalization_sessions","session_offers","training_jobs","models","state",
            "storage_current","storage_events","storage_seen"));
        var copies = Set.of("offer_events","current_offers","copy_state","rejected_events");
        tables.put("customer_offer_read", copies);
        tables.put("enterprise_offer_warehouse", copies);
        tables.put("campaign_execution", Set.of("execution_state","execution_tasks","execution_packages",
            "execution_history","execution_dispatches"));
        var agents = Set.of("records","artifacts","budgets","reservations","reservation_values","settlements");
        for (String role : "orchestrator research designer coordinator analyzer reflection feedback_orchestrator feedback_research feedback_analyzer feedback_reflection feedback_eligibility personalization_orchestrator personalization_context personalization_designer personalization_eligibility personalization_guide".split(" "))
            tables.put("ai_" + role, agents);
        return tables;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !Set.of("check","clear").contains(args[0])) throw new IllegalArgumentException("Expected check or clear");
        String url = System.getenv("DATABASE_URL");
        URI db = URI.create(Objects.requireNonNull(url));
        if (!Set.of("postgresql","postgres").contains(db.getScheme()) ||
            !Set.of("localhost","127.0.0.1").contains(db.getHost()) || db.getRawQuery()!=null ||
            !db.getPath().matches("/[a-zA-Z0-9_]+") || db.getUserInfo()==null ||
            !url.equals(System.getenv().getOrDefault("AI_DATABASE_URL",url)))
            throw new IllegalArgumentException("A single local application database is required");
        String broker = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS","127.0.0.1:9092");
        if (!broker.matches("(localhost|127\\.0\\.0\\.1):[0-9]{1,5}")) throw new IllegalArgumentException("A local broker is required");
        String[] auth=db.getRawUserInfo().split(":",2);
        String jdbc="jdbc:postgresql://"+db.getHost()+":"+(db.getPort()<0?5432:db.getPort())+db.getPath();
        try (var conn=DriverManager.getConnection(jdbc,URLDecoder.decode(auth[0],StandardCharsets.UTF_8),
                URLDecoder.decode(auth[1],StandardCharsets.UTF_8)); var statement=conn.createStatement();
             var kafka=Admin.create(Map.of("bootstrap.servers",broker,"default.api.timeout.ms",45000,"request.timeout.ms",10000))) {
            conn.setAutoCommit(false);
            var present=new TreeSet<String>();
            var allowed=owned();
            try(var rs=statement.executeQuery("SELECT schemaname,tablename FROM pg_catalog.pg_tables")) {
                while(rs.next()) if(allowed.getOrDefault(rs.getString(1),Set.of()).contains(rs.getString(2)))
                    present.add(rs.getString(1)+"."+rs.getString(2));
            }
            if(!present.contains("public.bureau_profiles") || !present.contains("marketing_offers.offers"))
                throw new IllegalStateException("Local lending schema missing");
            var topics=new HashSet<>(kafka.listTopics().names().get(20,TimeUnit.SECONDS));
            topics.retainAll(TOPICS);
            if (!args[0].equals("clear")) return;
            try(var rs=statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend'")){
                rs.next(); if(rs.getLong(1)!=0) throw new IllegalStateException("Close other database connections before resetting");
            }
            // No CASCADE: an unexpected dependency fails closed instead of clearing another application's data.
            statement.execute("SET LOCAL lock_timeout='5s'");
            statement.execute("TRUNCATE TABLE "+String.join(",",present)+" RESTART IDENTITY RESTRICT");
            if(!topics.isEmpty()){
                var offsets=new HashMap<TopicPartition,OffsetSpec>();
                for(var t:kafka.describeTopics(topics).allTopicNames().get(20,TimeUnit.SECONDS).values())
                    for(var p:t.partitions()) offsets.put(new TopicPartition(t.name(),p.partition()),OffsetSpec.latest());
                var cuts=new HashMap<TopicPartition,RecordsToDelete>();
                kafka.listOffsets(offsets).all().get(20,TimeUnit.SECONDS).forEach((p,o)->cuts.put(p,RecordsToDelete.beforeOffset(o.offset())));
                kafka.deleteRecords(cuts).all().get(40,TimeUnit.SECONDS);
            }
            conn.commit();
        }
    }
}

package com.lending.ai;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL verification uses an ephemeral native cluster and never connects to application databases. */
class PostgresStoreTest {
    @Test void migrationsLeadershipBudgetAndRestartPersistenceWorkOnPostgres()throws Exception{
        try(var pg=EmbeddedPostgres.start();var connection=pg.getPostgresDatabase().getConnection()){
            String url=connection.getMetaData().getURL(),user=connection.getMetaData().getUserName();String schema="ai_test_"+UUID.randomUUID().toString().replace("-","");
            try(var store=new Store(url,user,"",schema)){
                store.create("WORKFLOW","durable",obj("state","PLANNING"));
                assertThrows(IllegalStateException.class,()->new Store(url,user,"",schema));
                store.budget("w",new Store.Budget(4,6,1000,BigDecimal.ONE,Instant.now().plusSeconds(60).toString(),1,0,2,1,0,0));
                store.reserve("w","model:x",0,1,500,new BigDecimal(".5"));store.settle("w","model:x",100);
                assertEquals(100,store.budget("w").path("reservedTokens").asInt());
            }
            try(var reopened=new Store(url,user,"",schema)){assertEquals("PLANNING",reopened.get("WORKFLOW","durable").data().path("state").asText());}
        }
    }
}

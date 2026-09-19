/**
 * Business-operations regression covers legacy chronology/batches, strict APIs and terminal customer
 * create/edit; isolated simulations have a dedicated suite.
 */
package com.lending.engine;

import com.lending.engine.application.*;
import com.lending.engine.domain.Model.*;
import com.lending.engine.domain.CustomerRules;
import com.lending.engine.infrastructure.*;
import com.lending.engine.api.ApiServer;
import com.lending.engine.terminal.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.io.*;

/**
 * Business-operations regression covers legacy chronology/batches, strict APIs and terminal customer
 * create/edit; isolated simulations have a dedicated suite.
 */
class EngineTest {
    @TempDir Path temp;
    private SqlStore store;
    private CobolUnderwriter cobol;
    private CustomerEngine engine;
    private final Clock clock=Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"),ZoneOffset.UTC);
    /** Creates isolated test resources and wires the workflow under test. */
    @BeforeEach void setup(){
        store=new SqlStore("jdbc:h2:mem:"+UUID.randomUUID());
        cobol=new CobolUnderwriter(Path.of("../build/liuwbatch"),temp.resolve("scratch"));
        engine=new CustomerEngine(store,cobol,clock);
    }
    /** Closes test resources and removes only the temporary artifacts owned by the test. */
    @AfterEach void cleanup(){engine.close();store.close();}
    /** Builds customer input that satisfies the baseline demonstration policy. */
    static CustomerInput good(){return new CustomerInput("EXT-1","Alice Local",new BigDecimal("7000.00"),new BigDecimal("500.00"),760,0,
        new BigDecimal("20.00"),new BigDecimal("10000.00"),new BigDecimal("3000.00"),new BigDecimal("15000.00"),new BigDecimal("20000.00"),3,
        36,new BigDecimal("5000.00"),new BigDecimal("1200.00"),List.of("CHECKING"),false,false);}
    /** Builds a modified fixture for exercising version and eligibility changes. */
    static CustomerInput change(CustomerInput c,String field,Object value){var n=Json.MAPPER.valueToTree(c);((com.fasterxml.jackson.databind.node.ObjectNode)n).set(field,Json.MAPPER.valueToTree(value));return Json.read(n.toString(),CustomerInput.class);}
    /** Builds a supplied underwriting decision bound to the test profile. */
    static ImportDecision imported(Customer c,String id,Status status,String expiry){return new ImportDecision(c.id(),c.current().version(),Product.PERSONAL_LOAN,status,
        status==Status.ELIGIBLE?List.of():List.of("SOURCE_DECLINE"),"DEMO_BANK",id,"SOURCE-1","2026-09-16T12:00:00Z",expiry);}
    /** Verifies that compiled COBOL evaluates three products and preserves unknown vs zero. */
    @Test void compiledCobolEvaluatesThreeProductsAndPreservesUnknownVsZero(){
        assertEquals(74,CobolUnderwriter.encode(1,Product.PERSONAL_LOAN,good()).length());
        var results=cobol.evaluate(List.of(good(),change(good(),"monthlyIncomeUsd",null),change(good(),"monthlyIncomeUsd",0)));
        assertTrue(results.get(0).stream().allMatch(d->d.status()==Status.ELIGIBLE));
        assertTrue(results.get(1).stream().allMatch(d->d.status()==Status.INCOMPLETE));
        assertTrue(results.get(2).stream().allMatch(d->d.status()==Status.INELIGIBLE));
        assertEquals("SIM-2026-001",results.get(0).get(0).policyVersion());
    }
    /** Verifies that thresholds and auto collateral are product specific. */
    @Test void thresholdsAndAutoCollateralAreProductSpecific(){
        CustomerInput c=change(change(change(good(),"creditScore",660),"monthlyDebtPaymentsUsd",2800),"vehicleValueUsd",10000);
        var r=cobol.evaluate(List.of(c,change(c,"creditScore",659),change(c,"monthlyDebtPaymentsUsd",new BigDecimal("2800.01"))));
        assertEquals(Status.ELIGIBLE,r.get(0).get(0).status());
        assertTrue(r.get(1).get(0).reasons().contains("CREDIT_SCORE_BELOW_MINIMUM"));
        assertTrue(r.get(2).get(0).reasons().contains("DEBT_TO_INCOME_EXCEEDED"));
        assertEquals(Status.ELIGIBLE,r.get(1).get(1).status());
        assertTrue(r.get(0).get(2).reasons().contains("LOAN_TO_VALUE_EXCEEDED"));
    }
    /** Verifies that invalid financial values are rejected before COBOL. */
    @Test void invalidFinancialValuesAreRejectedBeforeCobol(){
        assertThrows(Problem.class,()->engine.create(change(good(),"monthlyIncomeUsd",-1)));
        assertThrows(Problem.class,()->engine.create(change(good(),"creditScore",999)));
        assertThrows(Problem.class,()->engine.create(change(good(),"autoLoanAmountUsd",new BigDecimal("12.123"))));
        assertEquals(0,engine.list(null,0,20).total());
    }
    /** Verifies that editing versions profiles and decisions with optimistic concurrency. */
    @Test void editingVersionsProfilesAndDecisionsWithOptimisticConcurrency(){
        Customer c=engine.create(good());assertEquals("SELF_REPORTED",c.current().creditInformationSource());
        Customer updated=engine.update(c.id(),new Update(1,change(good(),"creditScore",400)));
        assertEquals(2,updated.profiles().size());assertEquals(6,updated.decisions().size());
        assertEquals(Status.INELIGIBLE,updated.latest(Product.PERSONAL_LOAN).status());
        assertEquals(760,updated.profiles().get(0).data().creditScore());
        assertEquals(409,assertThrows(Problem.class,()->engine.update(c.id(),new Update(1,good()))).status);
    }
    /** Verifies that external reference uniqueness does not partially save. */
    @Test void externalReferenceUniquenessDoesNotPartiallySave(){
        engine.create(good());assertEquals(409,assertThrows(Problem.class,()->engine.create(good())).status);
        assertEquals(1,engine.list(null,0,20).total());
    }
    /** Verifies that imported decisions are idempotent bound to profile and atomic. */
    @Test void importedDecisionsAreIdempotentBoundToProfileAndAtomic(){
        Customer c=engine.create(good());ImportDecision d=imported(c,"DEC-1",Status.INELIGIBLE,"2026-10-16");
        Decision a=engine.importDecisions(List.of(d)).get(0);
        assertEquals(a.id(),engine.importDecisions(List.of(d)).get(0).id());
        assertEquals(4,engine.get(c.id()).decisions().size());
        assertEquals("IMPORTED",engine.get(c.id()).latest(Product.PERSONAL_LOAN).source());
        assertEquals(409,assertThrows(Problem.class,()->engine.importDecisions(List.of(imported(c,"DEC-1",Status.ELIGIBLE,"2026-10-16")))).status);
        ImportDecision missing=new ImportDecision("missing",1,Product.PERSONAL_LOAN,Status.ELIGIBLE,List.of(),"BANK","NOPE","1","2026-09-16T12:00:00Z","2026-10-16");
        assertThrows(Problem.class,()->engine.importDecisions(List.of(imported(c,"DEC-2",Status.ELIGIBLE,"2026-10-16"),missing)));
        assertEquals(4,engine.get(c.id()).decisions().size());
        engine.update(c.id(),new Update(1,good()));
        assertEquals(409,assertThrows(Problem.class,()->engine.importDecisions(List.of(imported(c,"DEC-3",Status.ELIGIBLE,"2026-10-16")))).status);
    }
    /** Verifies that population snapshot survives edits and keeps exact decision references. */
    @Test void populationSnapshotSurvivesEditsAndKeepsExactDecisionReferences(){
        Customer c=engine.create(good());Population p=engine.createPopulation(new PopulationRequest(List.of(Product.values()),null));
        assertEquals(1,p.eligibleCustomers());assertEquals(3,p.qualifiedPairs());
        var member=engine.members(p.id(),0,20).items().get(0);
        assertTrue(c.decisions().stream().anyMatch(d->d.id().equals(member.decisionId())));
        engine.update(c.id(),new Update(1,change(good(),"prescreenOptOut",true)));
        assertEquals(3,engine.members(p.id(),0,20).total());
        Population next=engine.createPopulation(new PopulationRequest(List.of(Product.values()),null));
        assertEquals(0,next.eligibleCustomers());assertEquals(3L,next.exclusions().get("PRESCREEN_OPT_OUT"));

        assertFalse(good().marketingOptIn());assertEquals(1,p.eligibleCustomers());
    }
    /** Verifies that unknown preference and expired assessments exclude without mutating history. */
    @Test void unknownPreferenceAndExpiredAssessmentsExcludeWithoutMutatingHistory(){
        Customer c=engine.create(change(good(),"prescreenOptOut",null));
        assertNull(engine.list(null,0,20).items().get(0).prescreenOptOut());
        assertEquals(0,engine.createPopulation(new PopulationRequest(List.of(Product.PERSONAL_LOAN),null)).eligibleCustomers());
        Profile profile=c.current();Decision d=c.latest(Product.PERSONAL_LOAN);
        assertTrue(CustomerRules.exclusionReasons(profile,d,LocalDate.parse("2026-12-01")).contains("ASSESSMENT_EXPIRED"));
        assertEquals(Status.ELIGIBLE,d.status());
    }
    /** Verifies that late older import cannot replace newer decision and expiry does not fallback. */
    @Test void lateOlderImportCannotReplaceNewerDecisionAndExpiryDoesNotFallback(){
        MutableClock time=new MutableClock(clock.instant());
        try(SqlStore db=new SqlStore("jdbc:h2:mem:chronology");CustomerEngine e=new CustomerEngine(db,cobol,time)){
            Customer c=e.create(good());time.instant=Instant.parse("2026-09-16T12:05:00Z");
            ImportDecision newest=new ImportDecision(c.id(),1,Product.PERSONAL_LOAN,Status.ELIGIBLE,List.of(),"BANK","NEW","B1","2026-09-16T12:04:00Z","2026-09-16");
            Decision effective=e.importDecisions(List.of(newest)).get(0);
            e.importDecisions(List.of(new ImportDecision(c.id(),1,Product.PERSONAL_LOAN,Status.INELIGIBLE,List.of("DECLINED"),"BANK","OLD","B1","2026-09-16T12:02:00Z","2026-10-16")));
            assertEquals(effective.id(),e.get(c.id()).latest(Product.PERSONAL_LOAN).id());
            assertEquals(1,e.createPopulation(new PopulationRequest(List.of(Product.PERSONAL_LOAN),null)).eligibleCustomers());
            time.instant=Instant.parse("2026-09-17T00:00:00Z");
            Population p=e.createPopulation(new PopulationRequest(List.of(Product.PERSONAL_LOAN),null));
            assertEquals(0,p.eligibleCustomers());assertEquals(1L,p.exclusions().get("ASSESSMENT_EXPIRED"));
            assertEquals(5,e.get(c.id()).decisions().size());
        }
    }
    /** Verifies that ten thousand customer batch completes with auditable population. */
    @Test @Timeout(120) void tenThousandCustomerBatchCompletesWithAuditablePopulation()throws Exception{
        long start=System.nanoTime();Job job=engine.simulate(new SimulationRequest(10000,20260916L));
        while(Set.of("QUEUED","RUNNING").contains(engine.job(job.id()).status()))Thread.sleep(25);
        job=engine.job(job.id());assertEquals("COMPLETED",job.status());
        assertEquals(30000,job.outcomes().values().stream().mapToLong(Long::longValue).sum());
        Population p=engine.createPopulation(new PopulationRequest(List.of(Product.values()),job.id()));
        assertEquals(10000,p.customerCount());assertTrue(p.eligibleCustomers()>0 && p.eligibleCustomers()<10000);
        assertEquals(p.qualifiedPairs(),engine.members(p.id(),0,200).total());
        assertTrue(p.exclusions().containsKey("PRESCREEN_OPT_OUT"));assertTrue(p.exclusions().containsKey("INCOMPLETE"));
        System.out.printf(Locale.ROOT,"Validated 10000 customers / 30000 COBOL decisions / %d eligible customers / %d pairs in %.2f seconds%n",p.eligibleCustomers(),p.qualifiedPairs(),(System.nanoTime()-start)/1e9);
    }
    /** Verifies that domain and terminal boundaries remain separate. */
    @Test void domainAndTerminalBoundariesRemainSeparate()throws Exception{
        try(var paths=Files.walk(Path.of("src/main/java/com/lending/engine/domain"))){
            for(Path p:paths.filter(x->x.toString().endsWith(".java")).toList()){
                String source=Files.readString(p);assertFalse(source.contains("import java.sql"));assertFalse(source.contains("import java.net"));assertFalse(source.contains("import com.fasterxml"));
            }
        }
        String ui=Files.readString(Path.of("src/main/java/com/lending/engine/terminal/TerminalServer.java"));
        assertFalse(ui.contains("CustomerEngine"));assertFalse(ui.contains("SqlStore"));assertFalse(ui.contains("CobolUnderwriter"));
        String cobolSource=Files.readString(Path.of("../app/cbl/LIUW01C.cbl"));
        assertFalse(cobolSource.contains("EXEC CICS"));assertFalse(cobolSource.contains("EXEC SQL"));
    }
    /** Verifies that deterministic generator produces mixed real rule outcomes. */
    @Test void deterministicGeneratorProducesMixedRealRuleOutcomes()throws Exception{
        assertEquals(Generator.generate(40,17),Generator.generate(40,17));
        assertNotEquals(Generator.generate(40,17),Generator.generate(40,18));
        Job j=engine.simulate(new SimulationRequest(40,17L));
        long end=System.currentTimeMillis()+15000;
        while(Set.of("QUEUED","RUNNING").contains(engine.job(j.id()).status()) && System.currentTimeMillis()<end)Thread.sleep(20);
        j=engine.job(j.id());assertEquals("COMPLETED",j.status());assertEquals(120,j.outcomes().values().stream().mapToLong(Long::longValue).sum());
        assertTrue(j.outcomes().get("PERSONAL_LOAN:ELIGIBLE")>0);assertTrue(j.outcomes().get("PERSONAL_LOAN:INELIGIBLE")>0);assertTrue(j.outcomes().get("PERSONAL_LOAN:INCOMPLETE")>0);
        assertEquals(40,engine.list(j.id(),0,20).total());
    }
    /** Verifies that SQL data persists across reopen. */
    @Test void sqlDataPersistsAcrossReopen(){
        String url="jdbc:h2:file:"+temp.resolve("persist");String id;
        try(SqlStore database=new SqlStore(url);CustomerEngine e=new CustomerEngine(database,cobol,clock)){id=e.create(good()).id();}
        try(SqlStore database=new SqlStore(url);CustomerEngine e=new CustomerEngine(database,cobol,clock)){assertEquals("Alice Local",e.get(id).current().data().displayName());}
    }
    /** Verifies that HTTP contract rejects unauthorized unknown and fractional integer inputs. */
    @Test void httpContractRejectsUnauthorizedUnknownAndFractionalIntegerInputs()throws Exception{
        try(ApiServer api=new ApiServer(engine,0,"test-local-secret")){
            api.start();HttpClient client=HttpClient.newHttpClient();String base="http://127.0.0.1:"+api.port()+"/api/v1/";
            assertEquals(401,client.send(HttpRequest.newBuilder(URI.create(base+"customers")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            for(String invalid:List.of("{\"displayName\":\"A\",\"surprise\":1}","{\"displayName\":\"A\",\"creditScore\":700.5}","{\"displayName\":\"A\",\"displayName\":\"B\"}","{\"displayName\":\"A\",\"creditScore\":\"700\"}","{\"displayName\":\"A\",\"existingProducts\":[null]}")){
                var req=HttpRequest.newBuilder(URI.create(base+"customers")).header("Authorization","Bearer test-local-secret").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(invalid)).build();
                assertEquals(422,client.send(req,HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            ApiClient terminal=new ApiClient(api.port(),"test-local-secret");var created=terminal.call("POST","customers",good());
            assertEquals(3,created.path("decisions").size());assertEquals(1,terminal.call("GET","customers",null).path("total").asInt());
        }
    }
    /** Verifies that real S3270 client completes manual customer form through API. */
    @Test void realS3270ClientCompletesManualCustomerFormThroughApi()throws Exception{
        try(ApiServer api=new ApiServer(engine,0,"terminal-test-secret")){
            api.start();try(TerminalServer terminal=new TerminalServer(0,new ApiClient(api.port(),"terminal-test-secret"))){
                terminal.start();Process process=new ProcessBuilder("s3270","-model","3279-2","127.0.0.1:"+terminal.port()).redirectError(temp.resolve("s3270.err").toFile()).start();
                try {
                    var reader=new BufferedReader(new InputStreamReader(process.getInputStream()));var writer=new PrintWriter(process.getOutputStream(),true);
                    command(writer,reader,"Wait(10,InputField)");command(writer,reader,"String(\"2\")");command(writer,reader,"Enter()");command(writer,reader,"Wait(5,InputField)");assertTrue(command(writer,reader,"Ascii()").contains("Lending Intelligence Engine"));
                    command(writer,reader,"String(\"7\")");command(writer,reader,"Enter()");command(writer,reader,"Wait(5,InputField)");
                    command(writer,reader,"String(\"1\")");command(writer,reader,"Enter()");command(writer,reader,"Wait(5,InputField)");
                    put(writer,reader,5,16,"Terminal Customer");put(writer,reader,7,28,"7000");put(writer,reader,8,28,"500");put(writer,reader,9,28,"760");
                    put(writer,reader,10,28,"0");put(writer,reader,11,28,"20");put(writer,reader,12,28,"10000");put(writer,reader,13,28,"3000");put(writer,reader,14,28,"15000");put(writer,reader,15,28,"20000");
                    command(writer,reader,"Enter()");command(writer,reader,"Wait(5,InputField)");put(writer,reader,5,28,"3");put(writer,reader,13,28,"N");put(writer,reader,14,28,"N");
                    command(writer,reader,"Enter()");command(writer,reader,"Wait(5,Output)");assertTrue(command(writer,reader,"Ascii()").contains("Confirm customer information"));
                    command(writer,reader,"Enter()");command(writer,reader,"Wait(5,Output)");String screen=command(writer,reader,"Ascii()");
                    assertTrue(screen.contains("PERSONAL_LOAN: ELIGIBLE"),screen);assertTrue(screen.contains("SELF_REPORTED"),screen);
                    assertEquals(1,engine.list(null,0,20).total());
                    command(writer,reader,"PF(4)");command(writer,reader,"Wait(5,InputField)");
                    command(writer,reader,"MoveCursor(7,28)");command(writer,reader,"EraseEOF()");command(writer,reader,"String(\"500\")");
                    command(writer,reader,"Enter()");command(writer,reader,"Wait(5,InputField)");command(writer,reader,"Enter()");command(writer,reader,"Wait(5,Output)");command(writer,reader,"Enter()");command(writer,reader,"Wait(5,Output)");
                    screen=command(writer,reader,"Ascii()");assertTrue(screen.contains("PERSONAL_LOAN: INELIGIBLE"),screen);assertTrue(screen.contains("profile v2"),screen);
                    command(writer,reader,"PF(3)");command(writer,reader,"Wait(5,InputField)");command(writer,reader,"String(\"7\")");command(writer,reader,"Enter()");command(writer,reader,"Wait(5,InputField)");command(writer,reader,"String(\"4\")");command(writer,reader,"Enter()");command(writer,reader,"Wait(5,InputField)");command(writer,reader,"Enter()");command(writer,reader,"Wait(5,Output)");
                    screen=command(writer,reader,"Ascii()");assertTrue(screen.contains("Eligible customers:"),screen);assertEquals(1,engine.populations().size());
                    command(writer,reader,"Quit()");
                }finally{process.destroyForcibly();}
            }
        }
    }
    /** Enters a test value into the selected field or JSON fixture. */
    private static void put(PrintWriter w,BufferedReader r,int row,int col,String value)throws Exception{command(w,r,"MoveCursor("+row+","+col+")");command(w,r,"String(\""+value+"\")");}
    /** Sends a command to the terminal emulator and returns its output. */
    private static String command(PrintWriter writer,BufferedReader reader,String command)throws Exception{
        writer.println(command);StringBuilder result=new StringBuilder();String line;
        while((line=reader.readLine())!=null){if(line.equals("ok"))return result.toString();if(line.equals("error"))throw new AssertionError(command+": "+result);result.append(line).append('\n');}
        if(command.equals("Quit()"))return result.toString();throw new AssertionError("s3270 closed: "+command+" "+result);
    }
    /** Groups mutable clock behavior for engine test operations. */
    private static final class MutableClock extends Clock {
        private Instant instant;
        /** Initializes the controlled test fixture and its supplied state. */
        MutableClock(Instant instant){this.instant=instant;}
        /** Returns the time zone configured for the controlled test clock. */
        public ZoneId getZone(){return ZoneOffset.UTC;}
        /** Returns a clock using the requested time zone for time-dependent tests. */
        public Clock withZone(ZoneId zone){return this;}
        /** Returns the controlled instant used by time-dependent tests. */
        public Instant instant(){return instant;}
    }
}

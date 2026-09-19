/**
 * Business-operations regression: Real COBOL, SQL, HTTP and 3270 checks for qualification, optimistic
 * finalization and reservation lifecycle.
 */
package com.lending.engine;

import com.lending.engine.application.CustomerEngine;
import com.lending.engine.domain.Model.*;
import com.lending.engine.infrastructure.*;
import com.lending.engine.marketing.application.MarketingEngine;
import com.lending.engine.marketing.domain.Marketing.*;
import com.lending.engine.marketing.infrastructure.CobolMarketingQualifier;
import com.lending.engine.api.ApiServer;
import com.lending.engine.terminal.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.math.BigDecimal;

/**
 * Business-operations regression: Real COBOL, SQL, HTTP and 3270 checks for qualification, optimistic
 * finalization and reservation lifecycle.
 */
class MarketingTest {
    @TempDir Path temp;
    SqlStore store;CustomerEngine customers;MarketingEngine marketing;
    MutableClock clock=new MutableClock(Instant.parse("2026-09-16T12:00:00Z"));
    /** Creates isolated test resources and wires the workflow under test. */
    @BeforeEach void setup(){store=new SqlStore("jdbc:h2:mem:"+UUID.randomUUID());customers=new CustomerEngine(store,new CobolUnderwriter(Path.of("../build/liuwbatch"),temp),clock);marketing=new MarketingEngine(store,new CobolMarketingQualifier(Path.of("../build/limkbatch"),temp),clock);}
    /** Closes the isolated test services, stores and processes. */
    @AfterEach void close(){customers.close();store.close();}
    /** Builds customer input that satisfies the baseline demonstration policy. */
    CustomerInput good(){return EngineTest.change(EngineTest.change(EngineTest.good(),"marketingOptIn",true),"externalReference",null);}
    /** Creates a customer in the isolated test store. */
    Customer customer(){return customers.create(good());}
    /** Creates the synthetic population used by this test. */
    Population population(){return customers.createPopulation(new PopulationRequest(List.of(Product.values()),null));}
    /** Creates a marketing qualification preview for the test customer population. */
    Run preview(Population p,String...campaigns){return marketing.preview(new RunRequest(UUID.randomUUID().toString(),p.id(),List.of(campaigns)));}
    /** Loads qualification result rows for the test run. */
    List<Qualification> rows(Run r){return marketing.results(r.id(),0,200,null).items();}
    /** Checks whether a qualification result contains the expected exclusion reason. */
    boolean reason(Run r,String reason){return rows(r).stream().anyMatch(q->q.reasons().contains(reason));}
    /** Builds the demonstration marketing policy used by the test. */
    void policy(int cooldown,int window,int max,int days){marketing.savePolicy(new PolicyUpdate(marketing.policy().version(),new PolicyInput(cooldown,window,max,days)));}
    /** Builds a campaign fixture with the requested limit or revision. */
    void campaign(String id,String field,Object value){Campaign c=marketing.campaign(id);var node=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(c.data());node.set(field,Json.MAPPER.valueToTree(value));marketing.saveCampaign(id,new CampaignUpdate(c.version(),Json.read(node.toString(),CampaignInput.class)));}
    /** Builds a catalog offer fixture with the requested terms or revision. */
    void offer(String id,String field,Object value){Offer o=marketing.offer(id);var node=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(o.data());node.set(field,Json.MAPPER.valueToTree(value));marketing.saveOffer(id,new OfferUpdate(o.version(),Json.read(node.toString(),OfferInput.class)));}
    /** Verifies that real COBOL qualifies and shares risk and one reservation across offers. */
    @Test void realCobolQualifiesAndSharesRiskAndOneReservationAcrossOffers(){
        customer();Run r=preview(population(),"DEMO-PL","DEMO-CC","DEMO-AL");
        assertEquals(6,rows(r).size());assertEquals(2,r.qualifiedOffers());assertEquals(1,r.reservationCount());
        assertEquals(1,rows(r).stream().map(Qualification::riskId).distinct().count());assertTrue(reason(r,"CUSTOMER_COOLDOWN"));
        assertTrue(marketing.reservations(r.id()).isEmpty());
        Run done=marketing.finalizeRun(r.id());assertEquals("FINALIZED",done.status());assertEquals(1,marketing.reservations(r.id()).size());
        assertEquals(done,marketing.finalizeRun(r.id()));assertEquals(rows(r),rows(done));
    }
    /** Verifies that consent unknown false and global suppressions fail closed with reasons. */
    @Test void consentUnknownFalseAndGlobalSuppressionsFailClosedWithReasons(){
        Customer a=customers.create(EngineTest.change(good(),"marketingOptIn",null));
        customers.create(EngineTest.change(good(),"marketingOptIn",false));Customer c=customer();
        marketing.saveSuppression(c.id(),new SuppressionUpdate(0,new SuppressionInput(true,"LOCAL_OPERATOR_HOLD",null)));
        Run r=preview(population(),"DEMO-PL");assertEquals(0,r.qualifiedCustomers());assertTrue(reason(r,"MARKETING_CONSENT_REQUIRED"));assertTrue(reason(r,"GLOBAL_SUPPRESSION"));
        assertTrue(rows(r).stream().filter(q->q.customerId().equals(c.id())).allMatch(q->"LOCAL_OPERATOR_HOLD".equals(q.suppressionReason())));
        assertEquals(3,rows(r).stream().map(Qualification::riskId).distinct().count());
        assertThrows(Problem.class,()->marketing.saveSuppression(a.id(),new SuppressionUpdate(1,new SuppressionInput(true,"STALE",null))));
    }
    /** Verifies that changed profiles and supplied declines cannot reuse frozen eligibility. */
    @Test void changedProfilesAndSuppliedDeclinesCannotReuseFrozenEligibility(){
        Customer c=customer();Population p=population();Run before=preview(p,"DEMO-PL");
        customers.update(c.id(),new Update(1,EngineTest.change(good(),"prescreenOptOut",true)));
        assertEquals(409,assertThrows(Problem.class,()->marketing.finalizeRun(before.id())).status);
        Run after=preview(p,"DEMO-PL");assertTrue(reason(after,"SOURCE_CHANGED"));assertTrue(reason(after,"PRESCREEN_OPT_OUT_OR_UNKNOWN"));
        Customer current=customers.get(c.id());customers.update(c.id(),new Update(2,good()));p=population();
        customers.importDecisions(List.of(new ImportDecision(c.id(),3,Product.PERSONAL_LOAN,Status.INELIGIBLE,List.of("BANK_DECLINE"),"BANK","D1","BANK-1",clock.instant().toString(),"2026-10-16")));
        Run declined=preview(p,"DEMO-PL");assertEquals(0,declined.qualifiedOffers());assertTrue(reason(declined,"UNDERWRITING_NOT_ELIGIBLE"));
    }
    /** Verifies that expired decisions and non member products are excluded. */
    @Test void expiredDecisionsAndNonMemberProductsAreExcluded(){
        customer();Population p=customers.createPopulation(new PopulationRequest(List.of(Product.PERSONAL_LOAN),null));
        assertTrue(reason(preview(p,"DEMO-CC"),"NOT_IN_PRESCREEN"));
        clock.advance(Duration.ofDays(31));Run r=preview(p,"DEMO-PL");assertEquals(0,r.qualifiedOffers());assertTrue(reason(r,"UNDERWRITING_EXPIRED"));
    }
    /** Verifies that campaign and offer boundaries version history and validation. */
    @Test void campaignAndOfferBoundariesVersionHistoryAndValidation(){
        customer();Population p=population();
        campaign("DEMO-PL","minimumTenureMonths",36);offer("DEMO-PL-1","minimumAmountUsd",10000);offer("DEMO-PL-1","maximumAmountUsd",10000);
        assertEquals(2,preview(p,"DEMO-PL").qualifiedOffers());
        assertThrows(Problem.class,()->offer("DEMO-PL-1","minimumAmountUsd",new BigDecimal("10000.01")));
        offer("DEMO-PL-1","minimumAmountUsd",new BigDecimal("9999.99"));offer("DEMO-PL-1","maximumAmountUsd",new BigDecimal("9999.99"));
        assertTrue(reason(preview(p,"DEMO-PL"),"AMOUNT_OUTSIDE_OFFER"));campaign("DEMO-PL","minimumTenureMonths",37);
        assertTrue(reason(preview(p,"DEMO-PL"),"TENURE_BELOW_MINIMUM"));
    }
    /** Verifies that catalog changes and policy changes require fresh preview. */
    @Test void catalogChangesAndPolicyChangesRequireFreshPreview(){
        customer();Population p=population();Run r=preview(p,"DEMO-PL");offer("DEMO-PL-1","active",false);
        assertEquals(409,assertThrows(Problem.class,()->marketing.finalizeRun(r.id())).status);
        Run next=preview(p,"DEMO-PL");assertTrue(reason(next,"OFFER_INACTIVE"));policy(0,30,3,7);
        assertEquals(409,assertThrows(Problem.class,()->marketing.finalizeRun(next.id())).status);
        assertEquals(2,((List<?>)marketing.history("offers","DEMO-PL-1")).size());
        assertThrows(Problem.class,()->marketing.savePolicy(new PolicyUpdate(1,new PolicyInput(1,30,3,7))));
        campaign("DEMO-PL","startsOn","2026-09-17");assertTrue(reason(preview(p,"DEMO-PL"),"CAMPAIGN_OUTSIDE_DATES"));
    }
    /** Verifies that campaign tenure ownership and requested amount remain independent checks. */
    @Test void campaignTenureOwnershipAndRequestedAmountRemainIndependentChecks(){
        customers.create(EngineTest.change(EngineTest.change(good(),"bankingTenureMonths",null),"existingProducts",null));
        campaign("DEMO-CC","minimumTenureMonths",1);Run r=preview(population(),"DEMO-CC");
        assertTrue(reason(r,"TENURE_UNKNOWN"));assertTrue(reason(r,"PRODUCT_OWNERSHIP_UNKNOWN"));assertTrue(reason(r,"AMOUNT_OUTSIDE_OFFER"));
    }
    /** Verifies that retry keys and risk identity are stable but new runs get new risk ids. */
    @Test void retryKeysAndRiskIdentityAreStableButNewRunsGetNewRiskIds(){
        customer();Population p=population();RunRequest request=new RunRequest("RETRY-1",p.id(),List.of("DEMO-PL"));Run a=marketing.preview(request);
        assertEquals(a,marketing.preview(request));assertEquals(rows(a),rows(marketing.preview(request)));
        assertThrows(Problem.class,()->marketing.preview(new RunRequest("RETRY-1",p.id(),List.of("DEMO-CC"))));
        assertNotEquals(rows(a).get(0).riskId(),rows(preview(p,"DEMO-PL")).get(0).riskId());
    }
    /** Verifies that competing finalizations cannot oversubscribe and cancellation releases. */
    @Test void competingFinalizationsCannotOversubscribeAndCancellationReleases(){
        customer();customer();campaign("DEMO-PL","capacity",1);Population p=population();Run a=preview(p,"DEMO-PL"),b=preview(p,"DEMO-PL");
        assertEquals(1,a.qualifiedCustomers());assertTrue(reason(a,"CAMPAIGN_CAPACITY"));
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            var one=workers.submit(()->finish(a));var two=workers.submit(()->finish(b));
            assertEquals(Set.of(200,409),Set.of(one.get(),two.get()));
        }catch(Exception e){throw new AssertionError(e);}finally{workers.shutdownNow();}
        Run winner=marketing.run(a.id()).status().equals("FINALIZED")?marketing.run(a.id()):marketing.run(b.id());
        var reservations=marketing.reservations(winner.id());assertEquals(1,reservations.size());
        assertEquals("CANCELLED",marketing.cancel(winner.id()).status());assertEquals(marketing.cancel(winner.id()),marketing.run(winner.id()));
        assertNotNull(marketing.reservations(winner.id()).get(0).releasedAt());assertEquals(1,preview(p,"DEMO-PL").qualifiedCustomers());
        assertThrows(Problem.class,()->marketing.finalizeRun(winner.id()));
    }
    /** Finalizes the prepared marketing preview for the test scenario. */
    int finish(Run run){try{marketing.finalizeRun(run.id());return 200;}catch(Problem p){return p.status;}}
    /** Verifies that rolling window survives expiry cooldown equality passes and cancel releases. */
    @Test void rollingWindowSurvivesExpiryCooldownEqualityPassesAndCancelReleases(){
        customer();policy(7,30,1,1);Population p=population();Run r=preview(p,"DEMO-PL");marketing.finalizeRun(r.id());
        clock.advance(Duration.ofDays(7));Run later=preview(p,"DEMO-PL");assertFalse(reason(later,"CUSTOMER_COOLDOWN"));assertTrue(reason(later,"CUSTOMER_WINDOW_LIMIT"));
        marketing.cancel(r.id());assertEquals(2,preview(p,"DEMO-PL").qualifiedOffers());
    }
    /** Verifies that preview expiration and suppression changes prevent finalization. */
    @Test void previewExpirationAndSuppressionChangesPreventFinalization(){
        Customer c=customer();Population p=population();Run first=preview(p,"DEMO-PL");
        marketing.saveSuppression(c.id(),new SuppressionUpdate(0,new SuppressionInput(true,"STOP",null)));
        assertThrows(Problem.class,()->marketing.finalizeRun(first.id()));
        marketing.saveSuppression(c.id(),new SuppressionUpdate(1,new SuppressionInput(false,"RELEASE",null)));
        Run second=preview(p,"DEMO-PL");clock.advance(Duration.ofMinutes(30));assertThrows(Problem.class,()->marketing.finalizeRun(second.id()));assertTrue(marketing.reservations(second.id()).isEmpty());
    }
    /** Verifies that API uses strict types and supports entire marketing workflow. */
    @Test void apiUsesStrictTypesAndSupportsEntireMarketingWorkflow()throws Exception{
        customer();Population p=population();
        try(ApiServer server=new ApiServer(customers,marketing,0,"local-test-token")){
            server.start();ApiClient api=new ApiClient(server.port(),"local-test-token");assertEquals(6,api.call("GET","marketing/offers",null).size());
            var r=api.call("POST","marketing/runs",new RunRequest("HTTP-1",p.id(),List.of("DEMO-PL")));String id=r.path("id").asText();
            assertEquals("FINALIZED",api.call("POST","marketing/runs/"+id+"/finalize",Map.of()).path("status").asText());
            assertEquals(2,api.call("GET","marketing/runs/"+id+"/results",null).path("total").asInt());
            var bad=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/api/v1/marketing/policy"))
                .header("Authorization","Bearer local-test-token").header("Content-Type","application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"expectedVersion\":1,\"policy\":{\"cooldownDays\":\"7\",\"rollingWindowDays\":30,\"maximumReservations\":3,\"reservationDays\":7}}")).build();
            assertEquals(422,HttpClient.newHttpClient().send(bad,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    /** Verifies that actual3270 can maintain catalog preview inspect risk and finalize. */
    @Test void actual3270CanMaintainCatalogPreviewInspectRiskAndFinalize()throws Exception{
        customer();population();try(ApiServer server=new ApiServer(customers,marketing,0,"terminal-token")){
            server.start();try(TerminalServer terminal=new TerminalServer(0,new ApiClient(server.port(),"terminal-token"))){
                terminal.start();Process process=new ProcessBuilder("s3270","-model","3279-2","127.0.0.1:"+terminal.port()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try{var r=new BufferedReader(new InputStreamReader(process.getInputStream()));var w=new PrintWriter(process.getOutputStream(),true);
                    cmd(w,r,"Wait(10,InputField)");cmd(w,r,"String(\"2\")");enter(w,r);cmd(w,r,"String(\"7\")");enter(w,r);cmd(w,r,"String(\"8\")");enter(w,r);assertTrue(cmd(w,r,"Ascii()").contains("DMG MARKETING"));
                    cmd(w,r,"String(\"1\")");enter(w,r);cmd(w,r,"String(\"1\")");enter(w,r);assertTrue(cmd(w,r,"Ascii()").contains("Demo APR"));
                    cmd(w,r,"MoveCursor(13,16)");cmd(w,r,"EraseEOF()");cmd(w,r,"String(\"8.5\")");enter(w,r);assertEquals(2,marketing.offer("DEMO-AL-1").version());
                    cmd(w,r,"PF(3)");cmd(w,r,"Wait(5,InputField)");cmd(w,r,"String(\"5\")");enter(w,r);cmd(w,r,"String(\"1\")");enter(w,r);enter(w,r);
                    String screen=cmd(w,r,"Ascii()");assertTrue(screen.contains("PREVIEW"),screen);cmd(w,r,"String(\"R\")");enter(w,r);cmd(w,r,"String(\"1\")");cmd(w,r,"Enter()");cmd(w,r,"Wait(5,Output)");
                    assertTrue(cmd(w,r,"Ascii()").contains("Risk ID:"));
                    cmd(w,r,"PF(3)");cmd(w,r,"Wait(5,InputField)");cmd(w,r,"String(\"6\")");enter(w,r);cmd(w,r,"String(\"1\")");enter(w,r);cmd(w,r,"String(\"F\")");enter(w,r);
                    screen=cmd(w,r,"Ascii()");assertTrue(screen.contains("FINALIZED"),screen);assertEquals("FINALIZED",marketing.runs(0,10).items().get(0).status());cmd(w,r,"Quit()");
                }finally{process.destroyForcibly();}
            }
        }
    }
    /** Verifies that ten thousand customers flow into marketing without bypassing consent. */
    @Test @Timeout(120) void tenThousandCustomersFlowIntoMarketingWithoutBypassingConsent()throws Exception{
        Job job=customers.simulate(new SimulationRequest(10000,20260916L));while(Set.of("QUEUED","RUNNING").contains(customers.job(job.id()).status()))Thread.sleep(20);
        assertEquals("COMPLETED",customers.job(job.id()).status());Population p=customers.createPopulation(new PopulationRequest(List.of(Product.values()),job.id()));
        Run r=preview(p,"DEMO-PL","DEMO-CC","DEMO-AL");assertEquals(p.eligibleCustomers(),r.customerCount());
        assertTrue(r.qualifiedCustomers()>0 && r.qualifiedCustomers()<r.customerCount());assertTrue(r.reasonCounts().containsKey("MARKETING_CONSENT_REQUIRED"));
        Run finalized=marketing.finalizeRun(r.id());assertEquals(r.qualifiedCustomers(),finalized.reservationCount());assertEquals(finalized.reservationCount(),marketing.reservations(r.id()).size());
        System.out.printf("Marketing validated %d pre-screen customers, %d offer evaluations and %d reservations from 10000 generated customers%n",r.customerCount(),marketing.results(r.id(),0,1,null).total(),r.reservationCount());
    }
    /** Verifies that additive migration and reopen retain customer catalog and reservation history. */
    @Test void additiveMigrationAndReopenRetainCustomerCatalogAndReservationHistory()throws Exception{
        Customer existing=customer();String url="jdbc:h2:file:"+temp.resolve("legacy");
        try(var connection=java.sql.DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){
            statement.execute("CREATE TABLE customers (id VARCHAR(40) PRIMARY KEY, external_ref VARCHAR(60) UNIQUE, batch_id VARCHAR(40), payload CLOB NOT NULL)");
            try(var insert=connection.prepareStatement("INSERT INTO customers VALUES(?,NULL,NULL,?)")){insert.setString(1,existing.id());insert.setString(2,Json.write(existing));insert.executeUpdate();}
        }
        String runId;String riskId;
        try(SqlStore database=new SqlStore(url);CustomerEngine engine=new CustomerEngine(database,new CobolUnderwriter(Path.of("../build/liuwbatch"),temp),clock)){
            MarketingEngine m=new MarketingEngine(database,new CobolMarketingQualifier(Path.of("../build/limkbatch"),temp),clock);
            assertEquals(existing,engine.get(existing.id()));Population p=engine.createPopulation(new PopulationRequest(List.of(Product.values()),null));
            Run r=m.preview(new RunRequest("PERSIST",p.id(),List.of("DEMO-PL")));runId=r.id();riskId=m.results(runId,0,1,null).items().get(0).riskId();m.finalizeRun(runId);
            m.savePolicy(new PolicyUpdate(1,new PolicyInput(0,30,5,2)));
        }
        try(SqlStore database=new SqlStore(url)){
            MarketingEngine m=new MarketingEngine(database,new CobolMarketingQualifier(Path.of("../build/limkbatch"),temp),clock);
            assertEquals(2,m.policy().version());assertEquals(6,m.offers().size());assertEquals("FINALIZED",m.run(runId).status());assertEquals(riskId,m.reservations(runId).get(0).riskId());
            assertEquals(existing,database.read(s->s.customer(existing.id())));
        }
    }
    /** Verifies that marketing domain and terminal do not access technical or business implementations. */
    @Test void marketingDomainAndTerminalDoNotAccessTechnicalOrBusinessImplementations()throws Exception{
        try(var paths=Files.walk(Path.of("src/main/java/com/lending/engine/marketing/domain"))){for(Path path:paths.filter(p->p.toString().endsWith(".java")).toList()){
            String code=Files.readString(path);assertFalse(code.contains("import java.sql"));assertFalse(code.contains("import java.net"));assertFalse(code.contains("import com.fasterxml"));}}
        String ui=Files.readString(Path.of("src/main/java/com/lending/engine/terminal/MarketingTerminal.java"));
        for(String forbidden:List.of("MarketingEngine","SqlStore","CobolMarketingQualifier"))assertFalse(ui.contains(forbidden));
        String cobol=Files.readString(Path.of("../app/cbl/LIMK01C.cbl"));for(String forbidden:List.of("EXEC SQL","EXEC CICS","OPEN INPUT","WRITE OUTPUT"))assertFalse(cobol.contains(forbidden));
    }
    /** Verifies that runtime failure cannot partially publish preview or reservations. */
    @Test void runtimeFailureCannotPartiallyPublishPreviewOrReservations(){
        customer();Population p=population();
        var unavailable=new MarketingEngine(store,inputs->{throw new IllegalStateException("Runtime unavailable");},clock);
        RunRequest request=new RunRequest("SAFE-RETRY",p.id(),List.of("DEMO-PL"));
        assertThrows(IllegalStateException.class,()->unavailable.preview(request));assertEquals(0,marketing.runs(0,10).total());
        Run r=marketing.preview(request);assertThrows(IllegalStateException.class,()->unavailable.finalizeRun(r.id()));
        assertEquals("PREVIEW",marketing.run(r.id()).status());assertTrue(marketing.reservations(r.id()).isEmpty());
        assertEquals("FINALIZED",marketing.finalizeRun(r.id()).status());
    }
    /** Submits the current terminal input and waits for the next screen. */
    static void enter(PrintWriter w,BufferedReader r)throws Exception{cmd(w,r,"Enter()");cmd(w,r,"Wait(5,InputField)");}
    /** Sends a command to the terminal emulator and returns its output. */
    static String cmd(PrintWriter w,BufferedReader r,String command)throws Exception{w.println(command);StringBuilder result=new StringBuilder();String line;while((line=r.readLine())!=null){if(line.equals("ok"))return result.toString();if(line.equals("error"))throw new AssertionError(command+": "+result);result.append(line).append('\n');}if(command.equals("Quit()"))return result.toString();throw new AssertionError("s3270 closed");}
    /** Groups mutable clock behavior for marketing test operations. */
    static final class MutableClock extends Clock{Instant at;
        /** Initializes the controlled test fixture and its supplied state. */
        MutableClock(Instant at){this.at=at;}
        /** Advances the controlled clock to exercise time-dependent behavior. */
        void advance(Duration d){at=at.plus(d);}
        /** Returns the time zone configured for the controlled test clock. */
        public ZoneId getZone(){return ZoneOffset.UTC;}
        /** Returns a clock using the requested time zone for time-dependent tests. */
        public Clock withZone(ZoneId zone){return this;}
        /** Returns the controlled instant used by time-dependent tests. */
        public Instant instant(){return at;}}
}

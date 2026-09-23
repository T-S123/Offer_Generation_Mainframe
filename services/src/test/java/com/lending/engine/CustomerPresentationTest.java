/**
 * Real TN3270 tests cover Customer/Business navigation, identifier guidance, consent blockers and stale
 * screens through a controlled HTTP boundary; financial authority is tested separately against PostgreSQL.
 */
package com.lending.engine;

import com.lending.engine.infrastructure.Json;
import com.lending.engine.terminal.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Real TN3270 tests cover Customer/Business navigation, identifier guidance, consent blockers and stale
 * screens through a controlled HTTP boundary; financial authority is tested separately against PostgreSQL.
 */
@Timeout(45)
class CustomerPresentationTest {
    HttpServer api;TerminalServer terminal;Process process;PrintWriter writer;BufferedReader reader;
    AtomicBoolean visible=new AtomicBoolean(),offline=new AtomicBoolean(),emptyFirst=new AtomicBoolean();
    AtomicInteger writes=new AtomicInteger();AtomicReference<JsonNode> submitted=new AtomicReference<>();
    AtomicReference<JsonNode> customerData=new AtomicReference<>();AtomicReference<String> pipelineState=new AtomicReference<>("OFFERS_PENDING"),bureauCustomer=new AtomicReference<>();
    ObjectNode offer;String customer="11111111-1111-1111-1111-111111111111";
    /** Creates isolated TN3270 and HTTP fixtures with controllable customer consent, progress and bureau lookups. */
    @BeforeEach void setup()throws Exception{
        offer=(ObjectNode)Json.MAPPER.valueToTree(Map.of("id","offer-1","customerId",customer,"product","PERSONAL_LOAN","qualification",Map.of("responseVersion",1,"catalogOfferId","DEMO-PL-1"),"preScreen",option(10000,12),"marketing",option(9500,10),"selection",Map.of("kind","ORIGINAL")));
        customerData.set(Json.MAPPER.valueToTree(Map.of("displayName","Demo Customer","externalReference","DEMO-CUST-001","marketingOptIn",true,"prescreenOptOut",false)));
        api=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);api.createContext("/api/v1/",x->{
            String path=x.getRequestURI().getPath();Object result;int status=200;
            if(path.endsWith("/selections")){submitted.set(Json.MAPPER.readTree(x.getRequestBody()));writes.incrementAndGet();result=Map.of("kind",submitted.get().path("kind").asText(),"terms",submitted.get().path("terms"),"selectedAt","2026-09-18T12:00:00Z");}
            else if(offline.get()&&path.contains("offers")){status=503;result=Map.of("error","Authority unavailable");}
            else if(path.endsWith("/offers/offer-1")){if(visible.get())result=offer;else{status=409;result=Map.of("error","Qualification withdrawn");}}
            else if(path.endsWith("/offers")){boolean empty=emptyFirst.get()&&!Optional.ofNullable(x.getRequestURI().getRawQuery()).orElse("").contains("after=page2");var page=new LinkedHashMap<String,Object>();page.put("items",visible.get()&&!empty?List.of(offer):List.of());page.put("nextAfter",empty?"page2":null);result=page;}
            else if(path.endsWith("/customers")&&x.getRequestMethod().equals("GET"))result=Map.of("items",List.of(Map.of("id",customer,"displayName","Demo Customer","version",1,"origin","MANUAL")),"total",1);
            else if(path.startsWith("/api/v1/bureau/profiles/")&&x.getRequestMethod().equals("POST")){bureauCustomer.set(path.substring(path.lastIndexOf('/')+1));result=Map.of("version",1,"source","GENERATED","facts",Map.of("fileStatus","MATCHED"));}
            else result=Map.of("id",customer,"profiles",List.of(Map.of("version",1,"data",customerData.get())),"currentAssessments",Map.of(),"pipeline",Map.of("state",pipelineState.get()));
            byte[] data=Json.write(result).getBytes(java.nio.charset.StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,data.length);x.getResponseBody().write(data);x.close();
        });api.start();terminal=new TerminalServer(0,new ApiClient(api.getAddress().getPort(),"test"));terminal.start();process=new ProcessBuilder("s3270","-model","3279-2","127.0.0.1:"+terminal.port()).redirectError(ProcessBuilder.Redirect.DISCARD).start();writer=new PrintWriter(process.getOutputStream(),true);reader=new BufferedReader(new InputStreamReader(process.getInputStream()));cmd("Wait(5,InputField)");
    }
    /** Selects a numbered terminal option in the test session. */
    static Object option(int amount,int apr){return Map.of("terms",Map.of("amountUsd",amount,"aprPct",apr,"annualFeeUsd",0,"termMonths",36),"metrics",Map.of("monthlyPaymentUsd",330,"totalCostUsd",1880),"validUntil","2026-12-31T23:59:59Z");}
    /** Closes test resources and removes only the temporary artifacts owned by the test. */
    @AfterEach void cleanup(){if(process!=null)process.destroyForcibly();if(terminal!=null)terminal.close();if(api!=null)api.stop(0);}
    /** Sends a command to the terminal emulator and returns its output. */
    String cmd(String value)throws Exception{return MarketingTest.cmd(writer,reader,value);}
    /** Captures the current terminal screen for assertions. */
    String screen()throws Exception{return cmd("Ascii()");}
    /** Submits the current terminal input and waits for the next screen. */
    void enter()throws Exception{cmd("Enter()");cmd("Wait(5,Unlock)");}
    /** Selects a terminal menu option in the test session. */
    void choose(String value)throws Exception{cmd("String(\""+value+"\")");enter();}
    /** Sends a function key to the test terminal. */
    void key(int n)throws Exception{cmd("PF("+n+")");cmd("Wait(5,Unlock)");}
    /** Opens the Customer workflow for the test customer. */
    void openCustomer()throws Exception{choose("01");choose("02");choose("1");}
    /** Navigates to the selected offer detail screen for assertions. */
    void detail()throws Exception{visible.set(true);openCustomer();key(6);choose("1");assertTrue(screen().contains("Compare Your Offers"),screen());}
    /** Captures the submitted request or terminal state for later assertions. */
    void capture(String name)throws Exception{Files.createDirectories(Path.of("target/presentation"));Files.writeString(Path.of("target/presentation/"+name+".txt"),screen());cmd("PrintText(html,file,\""+Path.of("target/presentation/"+name+".html").toAbsolutePath()+"\")");}
    /** Verifies that modes preserve fields and saved customer automatically reaches offers. */
    @Test void modesPreserveFieldsAndSavedCustomerAutomaticallyReachesOffers()throws Exception{
        assertTrue(screen().contains("Select a Mode"));capture("mode");choose("02");assertTrue(screen().contains("Business Simulation Engine"));capture("business-menu");key(3);choose("01");capture("customer-menu");choose("01");cmd("String(\"Unsubmitted Customer\")");Thread.sleep(2400);assertTrue(screen().contains("Unsubmitted Customer"));enter();enter();assertTrue(screen().contains("Confirm customer information"));enter();assertTrue(screen().contains("Application Progress"),screen());visible.set(true);
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);while(!screen().contains("My Qualified Offers")&&System.nanoTime()<end)Thread.sleep(100);assertTrue(screen().contains("My Qualified Offers"),screen());assertEquals(0,writes.get());capture("offers");
        key(3);key(3);choose("01");choose("04");assertTrue(screen().contains("open a demo profile first"),screen());
    }
    /** Verifies that customer compares confirms exact terms and can retain original. */
    @Test void customerComparesConfirmsExactTermsAndCanRetainOriginal()throws Exception{
        detail();assertTrue(screen().contains("ORIGINAL"));assertTrue(screen().contains("PERSONALIZED"));assertFalse(screen().contains("fitScore"));assertFalse(screen().contains("simulatedAcceptance"));capture("comparison");choose("P");assertTrue(screen().contains("Confirm Your Choice"));assertEquals(0,writes.get());capture("confirmation");enter();assertTrue(screen().contains("Selection Saved"),screen());assertEquals("PERSONALIZED",submitted.get().path("kind").asText());assertEquals(offer.path("marketing").path("terms"),submitted.get().path("terms"));assertEquals(1,submitted.get().path("sourceVersion").asLong());capture("receipt");enter();choose("1");choose("O");enter();assertEquals("ORIGINAL",submitted.get().path("kind").asText());assertEquals(2,writes.get());
    }
    /** Verifies that eligibility outage and changed terms never leave cached selectable offers. */
    @Test void eligibilityOutageAndChangedTermsNeverLeaveCachedSelectableOffers()throws Exception{
        detail();choose("P");((ObjectNode)offer.path("marketing").path("terms")).put("aprPct",11);enter();assertEquals(0,writes.get());assertTrue(screen().contains("Offer Information Unavailable"),screen());assertFalse(screen().contains("$9500"));key(3);assertTrue(screen().contains("11.00"));choose("P");visible.set(false);enter();assertEquals(0,writes.get());assertFalse(screen().contains("$9500"));key(3);key(3);assertTrue(screen().contains("No currently qualified offers"));visible.set(true);enter();choose("1");offline.set(true);enter();assertTrue(screen().contains("Cached offers are hidden"));assertFalse(screen().contains("$10000"));choose("O");assertEquals(0,writes.get());
    }
    /** Verifies that empty filtered pages still allow next and previous. */
    @Test void emptyFilteredPagesStillAllowNextAndPrevious()throws Exception{
        visible.set(true);emptyFirst.set(true);openCustomer();key(6);assertTrue(screen().contains("More records exist"));key(8);assertTrue(screen().contains("DEMO-PL-1"));key(7);assertFalse(screen().contains("DEMO-PL-1"));
    }
    /** Verifies that an external reference cannot trigger a bureau profile write and the generated UUID still opens it. */
    @Test void bureauLookupExplainsExternalReferenceAndAcceptsCustomerUuid()throws Exception{
        choose("02");choose("07");choose("9");choose("4");
        assertTrue(screen().contains("36-character UUID"),screen());
        choose("DEMO-CUST-001");assertTrue(screen().contains("Use the generated Customer ID, not External ref."),screen());assertNull(bureauCustomer.get());
        cmd("DeleteField()");choose(customer);assertEquals(customer,bureauCustomer.get());assertTrue(screen().contains("Bureau v1 GENERATED"),screen());
    }
    /** Verifies that saved consent and opt-out blockers explain missing offers without changing preferences or inventing a blocker. */
    @Test void progressExplainsSavedConsentAndPrescreenOptOut()throws Exception{
        pipelineState.set("NO_ELIGIBLE_OFFERS");var data=customerData.get().deepCopy();((ObjectNode)data).put("marketingOptIn",false);customerData.set(data);
        openCustomer();assertTrue(screen().contains("External ref: DEMO-CUST-001"),screen());assertTrue(screen().contains("Marketing opt-in: false"),screen());
        key(2);assertTrue(screen().contains("Marketing consent is not enabled"),screen());assertEquals(0,writes.get());
        key(3);data=customerData.get().deepCopy();((ObjectNode)data).put("marketingOptIn",true).put("prescreenOptOut",true);customerData.set(data);
        choose("05");assertTrue(screen().contains("Pre-screen opt-out is enabled"),screen());assertFalse(screen().contains("Marketing consent is not enabled"),screen());
        key(3);data=customerData.get().deepCopy();((ObjectNode)data).put("prescreenOptOut",false);customerData.set(data);
        choose("05");assertFalse(screen().contains("Marketing consent is not enabled"),screen());assertFalse(screen().contains("Pre-screen opt-out is enabled"),screen());assertEquals(0,writes.get());
    }
}

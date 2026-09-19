/**
 * CardDemo-style TN3270 Customer/Business modes use HTTP; synthetic experiments are isolated and only
 * customer waiting screens auto-refresh.
 */
package com.lending.engine.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lending.engine.infrastructure.Json;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * CardDemo-style TN3270 Customer/Business modes use HTTP; synthetic experiments are isolated and only
 * customer waiting screens auto-refresh.
 */
public final class TerminalServer implements AutoCloseable {
    private final ServerSocket listener;
    private final ApiClient api;
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final ExecutorService connections=new ThreadPoolExecutor(0,16,60,TimeUnit.SECONDS,new SynchronousQueue<>());
    private volatile boolean stopped;
    /** Initializes terminal server with the supplied configuration and dependencies. */
    public TerminalServer(int port,ApiClient api)throws IOException{this.api=api;listener=new ServerSocket();listener.bind(new InetSocketAddress("127.0.0.1",port));}
    /** Returns the bound local port so clients can connect to this listener. */
    public int port(){return listener.getLocalPort();}
    /** Starts the TN3270 listener and accepts independent terminal sessions. */
    public void start(){new Thread(()->{
        while(!stopped)try{
            Socket s=listener.accept();s.setSoTimeout(500);sockets.add(s);
            try{connections.submit(()->{try(s){new Session(s).run();}catch(IOException ignored){}finally{sockets.remove(s);}});}
            catch(RejectedExecutionException e){sockets.remove(s);s.close();}
        }catch(IOException e){if(!stopped)System.err.println("Terminal listener unavailable");}
    },"tn3270-listener").start();}
    /** Releases the resources owned by this component. */
    public void close(){stopped=true;try{listener.close();}catch(IOException ignored){}for(Socket s:sockets)try{s.close();}catch(IOException ignored){}connections.shutdownNow();}

    /** Groups session behavior for terminal server operations. */
    private final class Session {
        private final InputStream in;
        private final OutputStream out;
        private String screen="MODE",mode="",message="",customerId,jobId,populationId,lastBatch;
        private long lastInput=System.nanoTime(),lastRefresh;
        private int offset,version;
        private boolean editing,ready;
        private final Map<String,String> draft=new LinkedHashMap<>();
        private Map<String,String> defaults=new LinkedHashMap<>();
        private Map<String,String> retry=new LinkedHashMap<>();
        private Map<Integer,Field> fields=new LinkedHashMap<>();
        private JsonNode selected,list;
        private final MarketingTerminal marketing=new MarketingTerminal(api);
        private final BureauTerminal bureau=new BureauTerminal(api);
        private final StorageTerminal storage=new StorageTerminal(api);
        private final ExecutionTerminal execution=new ExecutionTerminal(api);
        private final CustomerTerminal presentation=new CustomerTerminal(api);
        private final SimulationTerminal simulation=new SimulationTerminal(api);
        /** Initializes session with the supplied configuration and dependencies. */
        Session(Socket socket)throws IOException{in=socket.getInputStream();out=socket.getOutputStream();}
        /** Negotiates a TN3270 session and handles input until the connection closes. */
        void run()throws IOException{
            out.write(new byte[]{(byte)255,(byte)251,0,(byte)255,(byte)253,0,(byte)255,(byte)251,25,(byte)255,(byte)253,25,(byte)255,(byte)253,24});out.flush();
            ByteArrayOutputStream record=new ByteArrayOutputStream();
            int value;
            while((value=read(record.size()==0))!=-1){
                if(value!=255){if(record.size()>8192)throw new IOException("Oversized terminal record");record.write(value);continue;}
                int command=read(false);if(command==-1)return;
                if(command==255){record.write(255);continue;}
                if(command==239){if(record.size()>0){handle(record.toByteArray());record.reset();}continue;}
                if(command==250){
                    ByteArrayOutputStream sub=new ByteArrayOutputStream();int b;
                    while((b=read(false))!=-1){if(b==255){int next=read(false);if(next==240)break;if(next==255)sub.write(255);}else sub.write(b);if(sub.size()>512)throw new IOException("Invalid negotiation");}
                    if(sub.size()>0 && sub.toByteArray()[0]==24 && !ready){ready=true;draw();}continue;
                }
                if(command>=251 && command<=254){int option=read(false);if(option==-1)return;
                    if(command==251 && option==24){out.write(new byte[]{(byte)255,(byte)250,24,1,(byte)255,(byte)240});out.flush();}
                    else if(command==253 && option!=0 && option!=25){out.write(new byte[]{(byte)255,(byte)252,(byte)option});out.flush();}
                    else if(command==251 && option!=0 && option!=25 && option!=24){out.write(new byte[]{(byte)255,(byte)254,(byte)option});out.flush();}
                }
            }
        }
        /** Reads the next TN3270 input record while handling protocol negotiation. */
        private int read(boolean idle)throws IOException{
            while(true)try{int value=in.read();lastInput=System.nanoTime();return value;}catch(SocketTimeoutException e){
                long now=System.nanoTime();if(now-lastInput>TimeUnit.MINUTES.toNanos(15))throw new EOFException("Idle session");
                if(idle&&ready&&screen.equals("CUSTOMER")&&presentation.autoRefresh()&&now-lastRefresh>=TimeUnit.SECONDS.toNanos(2))draw();
            }
        }
        /** Renders the mode-specific main menu. */
        private String menu(){return mode.equals("CUSTOMER")?"CUSTOMERMENU":"BUSINESS";}
        /** Clears customer and form state when changing terminal modes. */
        private void clearContext(){customerId=null;selected=null;list=null;editing=false;draft.clear();retry.clear();offset=0;presentation.open(null,true);}
        /** Rejects a customer-only action when no customer context is selected. */
        private void requireCustomer(){if(customerId==null)throw new IllegalArgumentException("Enter your information or open a demo profile first.");}
        /** Opens the customer presentation workflow for the selected customer. */
        private void customerView(boolean status){requireCustomer();presentation.open(customerId,status);screen="CUSTOMER";}
        /** Normalizes the entered terminal menu option for routing. */
        private String choice(Map<String,String> values){String v=values.getOrDefault("choice","").trim();return v.matches("0[0-9]")?v.substring(1):v;}
        /** Dispatches terminal keys and menu choices to Customer or Business screens. */
        private void handle(byte[] bytes)throws IOException{
            Map<String,String> values=new LinkedHashMap<>(defaults);
            int address=-1;ByteArrayOutputStream data=new ByteArrayOutputStream();
            for(int i=3;i<bytes.length;i++){
                int b=bytes[i]&255;
                if(b==0x11 && i+2<bytes.length){saveField(values,address,data);data.reset();address=decode(bytes[++i],bytes[++i]);}
                else data.write(b==0?0x40:b);
            }
            saveField(values,address,data);
            int aid=bytes[0]&255;message="";
            if(aid==0xf3 && !Set.of("MARKETING","BUREAU","STORAGE","EXECUTION","CUSTOMER","BUSINESS").contains(screen)){if(screen.equals("MODE")){out.write(new byte[]{(byte)0xf5,0x42,(byte)255,(byte)239});out.flush();throw new EOFException();}if(screen.equals("CUSTOMERMENU")){clearContext();mode="";screen="MODE";}else screen=menu();offset=0;retry.clear();draw();return;}
            try{
                if(screen.equals("BUSINESS")){String next=simulation.handle(aid,values);if(next!=null){screen=next;if(next.equals("MODE")){clearContext();mode="";}}}
                else if(screen.equals("CUSTOMER")){if(presentation.handle(aid,values))screen=menu();}
                else if(screen.equals("MARKETING")){if(marketing.handle(aid,values))screen="MENU";}
                else if(screen.equals("BUREAU")){if(bureau.handle(aid,values))screen="MENU";}
                else if(screen.equals("STORAGE")){if(storage.handle(aid,values))screen="MENU";}
                else if(screen.equals("EXECUTION")){if(execution.handle(aid,values))screen="MENU";}
                else if(aid==0xf7 || aid==0xf8){
                    if(Set.of("LIST","JOBS","POPLIST","MEMBERS").contains(screen))offset=Math.max(0,offset+(aid==0xf8?10:-10));
                    else if(screen.equals("FORM2") && aid==0xf7){draft.putAll(values);screen="FORM1";}
                    else if(screen.equals("REVIEW") && aid==0xf7)screen="FORM2";
                }else if(aid==0xf4 && screen.equals("DETAIL")){beginEdit();}
                else if(aid==0xf5 && screen.equals("DETAIL")){screen="HISTORY";}
                else if(aid==0xf6 && screen.equals("DETAIL")){if(mode.equals("CUSTOMER"))customerView(false);else{storage.customer(customerId);screen="STORAGE";}}
                else if(aid==0xf2 && screen.equals("DETAIL")){if(mode.equals("CUSTOMER"))customerView(true);else{execution.customer(customerId);screen="EXECUTION";}}
                else if(aid==0x7d){enter(values);}
                else if(aid!=0x6d){message="Use ENTER, F3, or the function keys shown below.";}
                retry.clear();
            }catch(RuntimeException e){retry=new LinkedHashMap<>(values);message=e.getMessage()==null?"The action could not be completed":e.getMessage();}
            draw();
        }
        /** Stores the submitted field value in the current customer form. */
        private void saveField(Map<String,String> values,int address,ByteArrayOutputStream data){
            Field f=fields.get(address);if(f!=null)values.put(f.key(),new String(data.toByteArray(),EBCDIC).stripTrailing());
        }
        /** Handles the Enter key for the active menu or customer form. */
        private void enter(Map<String,String> v){
            switch(screen){
                case "MODE" -> {String option=choice(v);if(!Set.of("1","2").contains(option))throw new IllegalArgumentException("Choose 01 Customer or 02 Business.");clearContext();mode=option.equals("1")?"CUSTOMER":"BUSINESS";simulation.reset();screen=menu();}
                case "CUSTOMERMENU" -> {offset=0;switch(choice(v)){
                    case "1" -> {editing=false;draft.clear();screen="FORM1";}
                    case "2" -> screen="LIST";
                    case "3" -> {requireCustomer();selected=api.call("GET","customers/"+customerId,null);screen="DETAIL";}
                    case "4" -> customerView(false);
                    case "5" -> customerView(true);
                    case "6" -> {requireCustomer();presentation.files(customerId);screen="CUSTOMER";}
                    default -> throw new IllegalArgumentException("Choose an option from 01 to 06.");}}
                case "MENU" -> {offset=0;switch(choice(v)){
                    case "1" -> {editing=false;draft.clear();screen="FORM1";}
                    case "2" -> screen="LIST";
                    case "3" -> {simulation.generate();screen="BUSINESS";}
                    case "4" -> screen="POPCREATE";
                    case "5" -> {simulation.reset();screen="BUSINESS";}
                    case "6" -> screen="POPLIST";
                    case "7" -> screen="IMPORT";
                    case "8" -> {marketing.reset();screen="MARKETING";}
                    case "9" -> {bureau.reset();screen="BUREAU";}
                    case "10" -> {storage.reset();screen="STORAGE";}
                    case "11" -> {execution.reset();screen="EXECUTION";}
                    default -> throw new IllegalArgumentException("Choose an option from 1 to 11.");}}
                case "FORM1" -> {draft.putAll(v);screen="FORM2";}
                case "FORM2" -> {draft.putAll(v);form();screen="REVIEW";}
                case "REVIEW" -> {
                    Object body=editing?Map.of("expectedVersion",version,"customer",form()):form();
                    selected=api.call(editing?"PUT":"POST",editing?"customers/"+customerId:"customers",body);
                    customerId=selected.path("id").asText();if(mode.equals("CUSTOMER")){customerView(true);message="Saved. Processing continues automatically.";}else{screen="DETAIL";message="Saved. Automatic Steps 2-7 queued; ENTER refreshes progress.";}}
                case "LIST" -> {
                    String id=v.get("id").trim();
                    String candidate=id.isEmpty()?pick(list.path("items"),v.get("choice")).path("id").asText():id;
                    selected=api.call("GET","customers/"+candidate,null);customerId=candidate;screen="DETAIL";}
                case "DETAIL" -> selected=api.call("GET","customers/"+customerId,null);
                case "HISTORY" -> screen="DETAIL";
                case "SIM" -> {selected=api.call("POST","simulations",Map.of("count",Integer.parseInt(v.get("count").trim()),"seed",Long.parseLong(v.get("seed").trim())));
                    jobId=selected.path("id").asText();lastBatch=jobId;screen="JOB";}
                case "JOBS" -> {jobId=pick(list,v.get("choice")).path("id").asText();lastBatch=jobId;screen="JOB";}
                case "JOB" -> {selected=api.call("GET","simulations/"+jobId,null);if(v.getOrDefault("choice","").trim().equals("P")){lastBatch=jobId;screen="POPCREATE";}}
                case "POPCREATE" -> {
                    Map<String,Object> body=new LinkedHashMap<>();body.put("products",products(v.get("products")));body.put("batchId",blank(v.get("batch")));
                    selected=api.call("POST","populations",body);populationId=selected.path("id").asText();screen="POPDETAIL";}
                case "POPLIST" -> {populationId=pick(list,v.get("choice")).path("id").asText();selected=api.call("GET","populations/"+populationId,null);screen="POPDETAIL";}
                case "POPDETAIL" -> {offset=0;screen="MEMBERS";}
                case "MEMBERS" -> {customerId=pick(list.path("items"),v.get("choice")).path("customerId").asText();selected=api.call("GET","customers/"+customerId,null);screen="DETAIL";}
                case "IMPORT" -> {
                    Map<String,Object> d=new LinkedHashMap<>();d.put("customerId",v.get("customerId").trim());d.put("profileVersion",Integer.parseInt(v.get("version").trim()));
                    d.put("product",product(v.get("product").trim()));d.put("status",switch(v.get("status").trim()){case "E"->"ELIGIBLE";case "D"->"INELIGIBLE";case "I"->"INCOMPLETE";default->throw new IllegalArgumentException("Use E, D, or I for status");});
                    for(String key:List.of("sourceSystem","sourceDecisionId","policyVersion","assessedAt","validUntil"))d.put(key,v.get(key).trim());
                    d.put("reasons",csv(v.get("reasons")));api.call("POST","underwriting/import",Map.of("decisions",List.of(d)));
                    customerId=v.get("customerId").trim();selected=api.call("GET","customers/"+customerId,null);screen="DETAIL";message="Supplied decision loaded. Source and profile version preserved.";}
            }
        }
        /** Loads the selected customer profile into an editable terminal form. */
        private void beginEdit(){
            selected=api.call("GET","customers/"+customerId,null);JsonNode p=current();version=p.path("version").asInt();draft.clear();
            p.path("data").fields().forEachRemaining(e->{JsonNode n=e.getValue();String value=n.isNull()?"":n.isBoolean()?(n.asBoolean()?"Y":"N"):n.isArray()?join(n):n.asText();
                if(!EBCDIC.newEncoder().canEncode(value))throw new IllegalArgumentException("This profile contains characters outside CP037; maintain it through the API.");
                draft.put(e.getKey(),value);});
            if(p.path("data").path("preferredChannels").isNull())draft.put("preferredChannels",join(selected.path("contact").path("preferredChannels")));editing=true;screen="FORM1";
        }
        /** Renders the customer data-entry form for the active page. */
        private Map<String,Object> form(){
            Map<String,Object> c=new LinkedHashMap<>();
            for(String key:List.of("displayName","externalReference"))c.put(key,blank(draft.get(key)));
            for(String key:List.of("monthlyIncomeUsd","monthlyDebtPaymentsUsd","creditUtilizationPct","personalLoanAmountUsd","requestedCardLimitUsd","autoLoanAmountUsd","vehicleValueUsd","depositBalanceUsd","monthlySpendUsd")){
                String s=blank(draft.get(key));try{c.put(key,s==null?null:new BigDecimal(s));}catch(NumberFormatException e){throw new IllegalArgumentException(key+": enter a number or leave blank");}}
            for(String key:List.of("creditScore","delinquencies12m","vehicleAgeYears","bankingTenureMonths")){
                String s=blank(draft.get(key));try{c.put(key,s==null?null:Integer.valueOf(s));}catch(NumberFormatException e){throw new IllegalArgumentException(key+": enter a whole number or leave blank");}}
            for(String key:List.of("marketingOptIn","prescreenOptOut")){
                String s=blank(draft.get(key));if(s!=null && !Set.of("Y","N").contains(s.toUpperCase(Locale.ROOT)))throw new IllegalArgumentException(key+": use Y, N, or blank");c.put(key,s==null?null:s.equalsIgnoreCase("Y"));}
            c.put("existingProducts",csv(draft.get("existingProducts")));c.put("preferredChannels",csv(draft.getOrDefault("preferredChannels","EMAIL")));return c;
        }
        /** Retrieves the current customer profile through the API. */
        private JsonNode current(){JsonNode p=selected.path("profiles");return p.get(p.size()-1);}
        /** Renders the active screen and sends the encoded buffer to the terminal. */
        private void draw()throws IOException{
            lastRefresh=System.nanoTime();
            Screen s=new Screen(retry);String title=switch(screen){case "FORM1","FORM2","REVIEW"->editing?"MAINTAIN CUSTOMER":"CUSTOMER INFORMATION";default->screen;};
            var now=LocalDateTime.now();s.text(0,1,"Tran: "+(mode.equals("BUSINESS")?"LB00":mode.equals("CUSTOMER")?"LC00":"LI00"),BLUE);
            s.text(1,1,"Prog: LIPRE01",BLUE);s.center(0,"Lending Intelligence Engine",YELLOW);s.center(1,mode.equals("BUSINESS")?"Business Services":mode.equals("CUSTOMER")?"Customer Services":"Local Demonstration",YELLOW);
            s.text(0,64,"Date: "+now.format(DateTimeFormatter.ofPattern("MM/dd/yy")),BLUE);s.text(1,64,"Time: "+now.format(DateTimeFormatter.ofPattern("HH:mm:ss")),BLUE);
            String footer="ENTER=Continue  F3=Main menu";
            try{
                switch(screen){
                    case "MODE" -> {s.center(3,"Select a Mode",WHITE);s.text(7,20,"01. Customer",BLUE);s.text(9,20,"02. Business",BLUE);s.text(15,8,"Local demo modes share data; this is not a login system.",WHITE);s.text(17,8,"US personal loans, credit cards and auto loans in USD.",WHITE);s.menuField();footer="ENTER=Continue  F3=Exit";}
                    case "CUSTOMERMENU" -> {s.center(3,"Customer Menu",WHITE);String[] options={"Enter my information","Open a demo customer profile","View / update my profile","View my qualified offers","Application progress","My marketing files / delivery status"};for(int i=0;i<options.length;i++)s.text(6+i,20,String.format("%02d. %s",i+1,options[i]),BLUE);s.text(14,8,customerId==null?"Enter your information to get started.":"Active profile: "+customerId,CYAN);s.text(17,8,"Offers are checked for current eligibility before display.",WHITE);s.menuField();footer="ENTER=Continue  F3=Change mode";}
                    case "BUSINESS" -> footer=simulation.draw(s);
                    case "CUSTOMER" -> footer=presentation.draw(s);
                    case "MARKETING" -> footer=marketing.draw(s);
                    case "BUREAU" -> footer=bureau.draw(s);
                    case "STORAGE" -> footer=storage.draw(s);
                    case "EXECUTION" -> footer=execution.draw(s);
                    case "MENU" -> {
                        s.center(3,"Business Operations",WHITE);String[] options={"Enter customer information","Browse / maintain active customers","Generate isolated simulation population","Create active pre-screen population","Business Simulation Engine","Review active pre-screen populations","Load supplied underwriting decision","Marketing qualification (DMG)","Bureau qualification / credit decision","Offer storage / enterprise warehouse","Campaign execution / outbound files"};for(int i=0;i<options.length;i++)s.text(5+i,18,String.format("%02d. %s",i+1,options[i]),BLUE);s.text(18,8,"Active operations. Use Simulation Engine for isolated experiments.",WHITE);s.menuField();footer="ENTER=Continue  F3=Business menu";}
                    case "FORM1" -> {
                        s.text(3,1,"Page 1/2. Credit details are SELF-REPORTED. Blank = unknown.",YELLOW);
                        String[][] f={{"displayName","Display name","60"},{"externalReference","External ref","60"},{"monthlyIncomeUsd","Gross monthly income","12"},{"monthlyDebtPaymentsUsd","Monthly debt payments","12"},{"creditScore","Credit score (300-850)","3"},{"delinquencies12m","Delinquencies (12 mo)","2"},{"creditUtilizationPct","Credit utilization %","6"},{"personalLoanAmountUsd","Personal loan amount","12"},{"requestedCardLimitUsd","Requested card limit","12"},{"autoLoanAmountUsd","Auto loan amount","12"},{"vehicleValueUsd","Vehicle value","12"}};
                        int row=5;for(String[] f0:f)s.field(f0[0],row++,f0[2].equals("60")?16:28,Integer.parseInt(f0[2]),draft.getOrDefault(f0[0],""),f0[1]);
                        s.text(18,1,"Income/debt are monthly USD. No SSN or bank account number needed.",WHITE);}
                    case "FORM2" -> {
                        s.text(3,1,"Page 2/2. Optional context is retained for future cohorting.",YELLOW);
                        s.field("vehicleAgeYears",5,28,2,draft.getOrDefault("vehicleAgeYears",""),"Vehicle age in years");
                        s.field("bankingTenureMonths",6,28,4,draft.getOrDefault("bankingTenureMonths",""),"Banking tenure months");
                        s.field("depositBalanceUsd",7,28,12,draft.getOrDefault("depositBalanceUsd",""),"Deposit balance USD");
                        s.field("monthlySpendUsd",8,28,12,draft.getOrDefault("monthlySpendUsd",""),"Monthly spend USD");
                        s.field("existingProducts",10,16,62,draft.getOrDefault("existingProducts",""),"Products held");
                        s.text(11,1,"Comma list: CHECKING,SAVINGS,CREDIT_CARD,PERSONAL_LOAN,AUTO_LOAN",BLUE);
                        s.field("marketingOptIn",13,28,1,draft.getOrDefault("marketingOptIn",""),"Marketing opt-in Y/N");
                        s.field("prescreenOptOut",14,28,1,draft.getOrDefault("prescreenOptOut",""),"Prescreen OPT-OUT Y/N");
                        s.text(16,1,"OPT-OUT Y excludes this customer. Blank preference stays unknown.",YELLOW);
                        s.field("preferredChannels",17,22,32,draft.getOrDefault("preferredChannels","EMAIL"),"Channels in order");s.text(18,1,"EMAIL,SMS,POSTAL; blank=none. Synthetic contacts; local files only.",WHITE);footer="ENTER=Review  F7=Previous page  F3=Cancel";}
                    case "REVIEW" -> {
                        s.text(4,1,"Confirm customer information before saving:",YELLOW);int row=6;
                        for(String key:List.of("displayName","monthlyIncomeUsd","monthlyDebtPaymentsUsd","creditScore","personalLoanAmountUsd","requestedCardLimitUsd","autoLoanAmountUsd","prescreenOptOut"))s.text(row++,1,key+": "+draft.getOrDefault(key,"unknown"),WHITE);
                        s.text(16,1,"Save starts underwriting, qualification, offers and outbound files.",CYAN);
                        s.text(17,1,"These are demonstration assessments, not actual credit approvals.",YELLOW);footer="ENTER=Save and assess  F7=Back  F3=Cancel";}
                    case "DETAIL" -> {renderDetail(s);footer=mode.equals("CUSTOMER")?"ENTER=Refresh F2=Progress F4=Edit F5=History F6=Offers F3=Menu":"ENTER=Refresh F2=Execution F4=Edit F5=History F6=Offers F3=Menu";}
                    case "HISTORY" -> {
                        s.text(4,1,"Immutable assessment history (most recent 12)",YELLOW);JsonNode ds=selected.path("decisions");int row=6;
                        for(int i=Math.max(0,ds.size()-12);i<ds.size();i++){JsonNode d=ds.get(i);s.text(row++,1,"v"+d.path("profileVersion").asText()+" "+d.path("product").asText()+" "+d.path("status").asText()+" "+d.path("source").asText()+" "+d.path("policyVersion").asText(),WHITE);}
                        s.text(19,1,"Full decision IDs, timestamps and source records are in the API.",BLUE);footer="ENTER=Customer detail  F3=Main menu";}
                    case "LIST" -> {
                        list=api.call("GET","customers?offset="+offset+"&limit=10",null);
                        s.text(4,1,"Customers "+offset+" - "+(offset+list.path("items").size())+" / "+list.path("total").asText(),YELLOW);int row=6,i=1;
                        for(JsonNode c:list.path("items"))s.text(row++,1,String.format("%2d %-30s v%-3s %s",i++,clip(c.path("displayName").asText(),30),c.path("version").asText(),c.path("origin").asText()),WHITE);
                        s.field("choice",17,18,2,"","Select row");s.field("id",18,18,36,"","Or customer ID");footer="ENTER=View  F7=Previous  F8=Next  F3=Main menu";}
                    case "SIM" -> {s.text(5,1,"Create mixed eligible, ineligible and incomplete synthetic profiles.",WHITE);
                        s.field("count",8,24,5,"1000","Customer count");s.field("seed",10,24,19,"20260916","Random seed");
                        s.text(13,1,"Count: 1-10000. The same seed reproduces the same input attributes.",BLUE);
                        s.text(15,1,"All profiles pass through the same COBOL rules as manual entries.",CYAN);}
                    case "JOB" -> {
                        selected=api.call("GET","simulations/"+jobId,null);s.text(4,1,"Batch: "+jobId,YELLOW);s.text(6,1,"Status: "+selected.path("status").asText()+"   Customers: "+selected.path("count").asText(),CYAN);
                        int row=8;var entries=selected.path("outcomes").fields();while(entries.hasNext()){var e=entries.next();s.text(row++,1,e.getKey()+"  "+e.getValue().asText(),WHITE);}
                        if(!selected.path("error").isNull())s.text(18,1,selected.path("error").asText(),RED);
                        s.field("choice",19,32,1,"","P=create prescreen population");footer="ENTER=Refresh or open population form  F3=Main menu";}
                    case "JOBS" -> {list=api.call("GET","simulations",null);renderRows(s,list,"Simulation jobs",true);footer="ENTER=View  F7=Previous  F8=Next  F3=Main menu";}
                    case "POPCREATE" -> {s.text(5,1,"Snapshot current, unexpired eligible assessments and preferences.",WHITE);
                        s.field("batch",8,22,36,lastBatch==null?"":lastBatch,"Simulation batch ID");s.text(9,1,"Leave blank to include all manual and simulated customers.",BLUE);
                        s.field("products",12,22,45,"PL,CC,AL","Products");s.text(13,1,"PL=Personal loan  CC=Credit card  AL=Auto loan",BLUE);
                        s.text(16,1,"Later profile edits do not change a saved population snapshot.",YELLOW);}
                    case "POPLIST" -> {list=api.call("GET","populations",null);renderRows(s,list,"Saved population snapshots",false);footer="ENTER=View  F7=Previous  F8=Next  F3=Main menu";}
                    case "POPDETAIL" -> {
                        selected=api.call("GET","populations/"+populationId,null);s.text(4,1,"Population: "+populationId,YELLOW);
                        s.text(6,1,"Customers considered: "+selected.path("customerCount").asText(),WHITE);s.text(7,1,"Eligible customers: "+selected.path("eligibleCustomers").asText()+"  Qualified product pairs: "+selected.path("qualifiedPairs").asText(),CYAN);
                        s.text(9,1,"Exclusions (per product; reasons can overlap)",BLUE);int row=10;var es=selected.path("exclusions").fields();while(es.hasNext() && row<20){var e=es.next();s.text(row++,1,e.getKey()+": "+e.getValue().asText(),WHITE);}footer="ENTER=Browse members  F3=Main menu";}
                    case "MEMBERS" -> {
                        list=api.call("GET","populations/"+populationId+"/members?offset="+offset+"&limit=10",null);s.text(4,1,"Frozen members "+offset+" / "+list.path("total").asText(),YELLOW);int row=6,i=1;
                        for(JsonNode m:list.path("items"))s.text(row++,1,i+++" "+m.path("customerId").asText()+" v"+m.path("profileVersion").asText()+" "+m.path("product").asText(),WHITE);
                        s.field("choice",18,20,2,"","View current record");footer="ENTER=Customer  F7=Previous  F8=Next  F3=Main menu";}
                    case "IMPORT" -> {
                        s.text(3,1,"Operator: load a supplied assessment. Existing history is retained.",YELLOW);
                        s.field("customerId",5,24,36,customerId==null?"":customerId,"Customer ID");s.field("version",6,24,5,selected!=null && selected.has("profiles")?current().path("version").asText():"1","Profile version");
                        s.field("product",7,24,2,"PL","Product PL/CC/AL");s.field("status",8,24,1,"E","Status E/D/I");
                        s.field("sourceSystem",9,24,40,"DEMO_SOURCE","Source system");s.field("sourceDecisionId",10,24,40,"","Source decision ID");
                        s.field("policyVersion",11,24,30,"IMPORT-1","Source policy version");
                        s.field("assessedAt",12,24,35,Instant.now().toString(),"Assessed at UTC");s.field("validUntil",13,24,10,LocalDate.now().plusDays(30).toString(),"Valid until YYYY-MM-DD");
                        s.field("reasons",15,15,62,"","Reason codes");s.text(17,1,"Comma-separated reason codes. D/I requires at least one reason.",BLUE);
                        s.text(19,1,"JSON files: scripts/run.ps1 -ImportDecisions <path>",WHITE);footer="ENTER=Load decision  F3=Cancel";}
                }
            }catch(RuntimeException e){s.text(5,1,"Unable to load screen data. Press F3 to return.",RED);message=e.getMessage();}
            s.text(21,1,clip(message,78),RED);s.text(23,1,footer,YELLOW);
            fields=s.fields;defaults=s.defaults;out.write(s.bytes());out.flush();
        }
        /** Renders detailed customer information and underwriting results. */
        private void renderDetail(Screen s){
            JsonNode p=current(),d=p.path("data");s.text(4,1,d.path("displayName").asText()+" | profile v"+p.path("version").asText(),YELLOW);
            s.text(5,1,"ID: "+customerId,BLUE);s.text(6,1,"Credit data: "+p.path("creditInformationSource").asText()+"  Origin: "+p.path("origin").asText(),WHITE);
            int row=8;for(String product:List.of("PERSONAL_LOAN","CREDIT_CARD","AUTO_LOAN")){
                JsonNode latest=selected.path("currentAssessments").get(product);
                if(latest!=null){s.text(row++,1,product+": "+latest.path("status").asText()+" / "+latest.path("source").asText(),CYAN);
                    s.text(row++,3,clip(join(latest.path("reasons")),74),WHITE);s.text(row++,3,"Policy "+latest.path("policyVersion").asText()+"  Valid through "+latest.path("validUntil").asText(),BLUE);}
            }
            s.text(18,1,"Opt-out: "+d.path("prescreenOptOut").asText("UNKNOWN")+" | Synthetic channels: "+join(selected.path("contact").path("preferredChannels")),YELLOW);
            var offers=selected.path("offerView");s.text(17,1,"Automatic workflow: "+selected.path("pipeline").path("state").asText("LEGACY_PROFILE")+(mode.equals("CUSTOMER")?" | F2=progress":" | F2=execution"),BLUE);
            String choice="none";for(var o:offers.path("items"))if(!o.path("selection").isNull())choice=o.path("selection").path("kind").asText();
            s.text(19,1,"Offers loaded (up to 3): "+offers.path("items").size()+" | Selected: "+choice+" | F6=view",CYAN);
            s.field("refresh",20,20,1,"","Refresh");
        }
        /** Renders paginated customer or workflow result rows. */
        private void renderRows(Screen s,JsonNode rows,String title,boolean jobs){
            s.text(4,1,title+" ("+rows.size()+")",YELLOW);int row=6;
            for(int i=offset;i<Math.min(offset+10,rows.size());i++){JsonNode n=rows.get(i);s.text(row++,1,(i-offset+1)+" "+n.path("id").asText()+" "+(jobs?n.path("status").asText():n.path("eligibleCustomers").asText()+" eligible"),WHITE);}
            s.field("choice",18,18,2,"","Select row");
        }
        /** Returns the selected displayed row after validating the entered row number. */
        private JsonNode pick(JsonNode rows,String choice){
            int n;try{n=Integer.parseInt(choice.trim())-1;}catch(Exception e){throw new IllegalArgumentException("Enter a displayed row number");}
            if(n<0||n>=10)throw new IllegalArgumentException("Choose a displayed row from 1 to 10");
            if(screen.equals("JOBS")||screen.equals("POPLIST"))n+=offset;
            if(n<0||n>=rows.size())throw new IllegalArgumentException("Choose a displayed row");return rows.get(n);
        }
    }
    /** Carries field data for terminal server operations. */
    private record Field(String key,int length){}
    private static final Charset EBCDIC=Charset.forName("IBM037");
    private static final int BLUE=0xf1,RED=0xf2,CYAN=0xf5,YELLOW=0xf6,WHITE=0xf7;
    private static final int[] ADDRESS={0x40,0xc1,0xc2,0xc3,0xc4,0xc5,0xc6,0xc7,0xc8,0xc9,0x4a,0x4b,0x4c,0x4d,0x4e,0x4f,0x50,0xd1,0xd2,0xd3,0xd4,0xd5,0xd6,0xd7,0xd8,0xd9,0x5a,0x5b,0x5c,0x5d,0x5e,0x5f,0x60,0x61,0xe2,0xe3,0xe4,0xe5,0xe6,0xe7,0xe8,0xe9,0x6a,0x6b,0x6c,0x6d,0x6e,0x6f,0xf0,0xf1,0xf2,0xf3,0xf4,0xf5,0xf6,0xf7,0xf8,0xf9,0x7a,0x7b,0x7c,0x7d,0x7e,0x7f};
    /** Decodes a TN3270 input record into the terminal command and field values. */
    private static int decode(byte a,byte b){return ((a&0xc0)==0)?((a&0x3f)<<8)|(b&255):((a&63)<<6)|(b&63);}
    /** Truncates text to the available terminal display width. */
    private static String clip(String s,int n){return s==null?"":s.length()>n?s.substring(0,n):s;}
    /** Checks whether an optional terminal field has no entered value. */
    private static String blank(String s){return s==null||s.isBlank()?null:s.trim();}
    /** Converts comma-separated terminal input into a list of values. */
    private static List<String> csv(String s){return blank(s)==null?List.of():Arrays.stream(s.split(",")).map(String::trim).filter(x->!x.isEmpty()).toList();}
    /** Joins a collection of values for display in a terminal field. */
    private static String join(JsonNode values){List<String> v=new ArrayList<>();values.forEach(n->v.add(n.asText()));return String.join(",",v);}
    /** Maps a lending product between its API value and terminal display representation. */
    private static String product(String s){return switch(s){case "PL","PERSONAL_LOAN"->"PERSONAL_LOAN";case "CC","CREDIT_CARD"->"CREDIT_CARD";case "AL","AUTO_LOAN"->"AUTO_LOAN";default->throw new IllegalArgumentException("Use product codes PL, CC, AL");};}
    /** Parses the lending products selected in a terminal field. */
    private static List<String> products(String s){return csv(s).stream().map(TerminalServer::product).toList();}
    /** Groups screen behavior for terminal server operations. */
    static final class Screen {
        private final ByteArrayOutputStream b=new ByteArrayOutputStream();
        private final Map<Integer,Field> fields=new LinkedHashMap<>();
        private final Map<String,String> defaults=new LinkedHashMap<>();
        private final Map<String,String> retry;
        /** Initializes screen with the supplied configuration and dependencies. */
        Screen(Map<String,String> retry){this.retry=retry;b.write(0xf5);b.write(0xc3);sba(1919);b.write(0x1d);b.write(0x60);}
        /** Writes a 3270 Set Buffer Address command for the requested screen position. */
        void sba(int position){b.write(0x11);b.write(ADDRESS[(position>>6)&63]);b.write(ADDRESS[position&63]);}
        /** Writes the 3270 color attribute used by subsequent screen content. */
        void color(int color){b.write(0x28);b.write(0x42);b.write(color);}
        /** Places text at a specified location in the 3270 screen buffer. */
        void text(int row,int col,String text,int color){sba(row*80+col);color(color);b.writeBytes(clip(text,80-col).getBytes(EBCDIC));}
        /** Places text centered across the terminal screen. */
        void center(int row,String text,int color){String value=clip(text,78);text(row,(80-value.length())/2,value,color);}
        /** Adds the editable menu-selection field to the terminal screen. */
        void menuField(){field("choice",20,53,2,"","");text(20,14,"Please select an option :",CYAN);}
        /** Adds a protected or editable 3270 field at the specified position. */
        void field(String key,int row,int col,int length,String value,String label){
            text(row,1,clip(label,col-3),CYAN);int start=row*80+col;sba(start-1);color(BLUE);b.write(0x1d);b.write(0xc1);
            String v=clip(retry.getOrDefault(key,value),length);b.writeBytes((v+" ".repeat(length-v.length())).getBytes(EBCDIC));b.write(0x1d);b.write(0x60);
            fields.put(start,new Field(key,length));defaults.put(key,v);
        }
        /** Returns the encoded 3270 screen buffer for transmission. */
        byte[] bytes(){if(!fields.isEmpty()){sba(fields.keySet().iterator().next());b.write(0x13);}ByteArrayOutputStream wire=new ByteArrayOutputStream();for(byte x:b.toByteArray()){wire.write(x&255);if((x&255)==255)wire.write(255);}wire.write(255);wire.write(239);return wire.toByteArray();}
    }
}

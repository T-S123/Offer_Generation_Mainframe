/** Presents Steps 3-5 through HTTP-backed terminal screens and distinguishes generated customer IDs from external references. */
package com.lending.engine.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.lending.engine.infrastructure.Json;
import java.math.BigDecimal;
import java.util.*;

/** Presents Steps 3-5 through HTTP-backed terminal screens and distinguishes generated customer IDs from external references. */
final class BureauTerminal {
    private static final int BLUE=0xf1,CYAN=0xf5,YELLOW=0xf6,WHITE=0xf7;
    private final ApiClient api;private String view="HOME",runId,customerId,requestId,batchId,after="";private int offset,sourceAfter=-1;
    private JsonNode rows,selected,qualification;private final Deque<String> previous=new ArrayDeque<>();private final Deque<Integer> sourcePrevious=new ArrayDeque<>();
    private final ResponseTerminal responses;
    private final OffersTerminal offers;
    /** Initializes bureau terminal with the supplied configuration and dependencies. */
    BureauTerminal(ApiClient api){this.api=api;this.responses=new ResponseTerminal(api);this.offers=new OffersTerminal(api);}
    /** Clears the screen state before beginning a new terminal interaction. */
    void reset(){view="HOME";offset=0;after="";sourceAfter=-1;previous.clear();sourcePrevious.clear();}
    /**
     * Processes terminal input through HTTP APIs, rejecting external references where a generated
     * customer UUID is required.
     */
    boolean handle(int aid,Map<String,String> values){
        if(view.equals("DECISIONS")){if(responses.handle(aid,values))reset();return false;}
        if(view.equals("OFFERS")){if(offers.handle(aid,values))reset();return false;}
        if(aid==0xf3){if(view.equals("HOME"))return true;reset();return false;}
        if(aid==0xf7||aid==0xf8){
            if(view.equals("RUNS"))offset=Math.max(0,offset+(aid==0xf8?10:-10));
            else if(view.equals("SOURCES")){if(aid==0xf8&&rows!=null&&!rows.isEmpty()){sourcePrevious.push(sourceAfter);sourceAfter=rows.get(rows.size()-1).path("ordinal").asInt();}else if(aid==0xf7&&!sourcePrevious.isEmpty())sourceAfter=sourcePrevious.pop();}
            else if(Set.of("REQUESTS","BATCHES","ITEMS").contains(view)){if(aid==0xf8&&rows!=null&&!rows.isEmpty()){previous.push(after);after=rows.get(rows.size()-1).path("requestId").asText();}else if(aid==0xf7&&!previous.isEmpty())after=previous.pop();}
            return false;
        }
        if(aid!=0x7d)return false;
        switch(view){
            case "HOME"->{switch(value(values,"choice")){case "1"->view="RUNS";case "2"->view="REQUESTS";case "3"->view="BATCHES";case "4"->view="PROFILE-ID";case "5"->view="HEALTH";case "6"->{responses.reset();view="DECISIONS";}case "7"->{offers.reset();view="OFFERS";}default->throw new IllegalArgumentException("Choose 1-7");}}
            case "RUNS"->{selected=pick(values);if(!selected.path("status").asText().equals("FINALIZED"))throw new IllegalArgumentException("Finalize Step 2 before bureau qualification");runId=selected.path("id").asText();sourceAfter=-1;view="SOURCES";}
            case "SOURCES"->{if(value(values,"choice").equalsIgnoreCase("B")){batchId=UUID.randomUUID().toString();selected=api.call("POST","bureau/batches",Map.of("requestId",batchId,"runIds",List.of(runId)));view="BATCH";}
                else{qualification=pick(values).path("qualification");customerId=qualification.path("customerId").asText();requestId=UUID.randomUUID().toString();view="QUALIFICATION";}}
            case "QUALIFICATION"->{switch(value(values,"action").toUpperCase(Locale.ROOT)){case "Q"->{selected=api.call("POST","bureau/requests",Map.of("requestId",requestId,"source",Map.of("runId",runId,"riskId",qualification.path("riskId").asText(),"qualificationId",qualification.path("id").asText())));view="REQUEST";}
                case "P"->{profile();view="PROFILE";}default->throw new IllegalArgumentException("Q=qualify or P=bureau profile");}}
            case "REQUESTS","ITEMS"->{selected=pick(values);requestId=selected.path("requestId").asText();view="REQUEST";}
            case "BATCHES"->{selected=pick(values);batchId=selected.path("requestId").asText();view="BATCH";}
            case "BATCH"->{if(value(values,"action").equalsIgnoreCase("I")){after="";view="ITEMS";}}
            case "PROFILE-ID"->{String id=value(values,"customerId");if(!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("Use the generated Customer ID, not External ref. Customer > 02 shows the ID.");customerId=id.toLowerCase(Locale.ROOT);profile();view="PROFILE";}
            case "PROFILE"->{var facts=Json.MAPPER.createObjectNode();facts.put("fileStatus",value(values,"fileStatus").toUpperCase(Locale.ROOT));
                for(String key:List.of("creditScore","utilizationPct","delinquencies12m","inquiries6m","oldestAccountMonths")){String v=value(values,key);if(v.isEmpty())facts.putNull(key);else facts.put(key,Integer.parseInt(v));}
                String debt=value(values,"monthlyDebtUsd"),bankrupt=value(values,"bankruptcy"),reported=value(values,"reportedAt");if(debt.isEmpty())facts.putNull("monthlyDebtUsd");else facts.put("monthlyDebtUsd",new BigDecimal(debt));
                if(bankrupt.isEmpty())facts.putNull("bankruptcy");else if(Set.of("Y","N").contains(bankrupt.toUpperCase(Locale.ROOT)))facts.put("bankruptcy",bankrupt.equalsIgnoreCase("Y"));else throw new IllegalArgumentException("Bankruptcy: Y/N or blank");
                if(reported.isEmpty())facts.putNull("reportedAt");else facts.put("reportedAt",reported);
                selected=api.call("PUT","bureau/profiles/"+customerId,Map.of("expectedVersion",selected.path("version").asInt(),"source","LOCAL_EDIT","facts",facts));view="PROFILE-SAVED";}
            case "PROFILE-SAVED"->view="HOME";
        }
        return false;
    }
    /** Loads the selected customer independent bureau profile through the API. */
    private void profile(){selected=api.call("POST","bureau/profiles/"+customerId,Map.of());}
    /** Returns the trimmed terminal field value, using an empty value when omitted. */
    private static String value(Map<String,String> values,String key){return values.getOrDefault(key,"").trim();}
    /** Returns the selected displayed row after validating the entered row number. */
    private JsonNode pick(Map<String,String> values){int n;try{n=Integer.parseInt(value(values,"choice"))-1;}catch(Exception e){throw new IllegalArgumentException("Choose a displayed row");}if(n<0||n>=rows.size())throw new IllegalArgumentException("Choose a displayed row");return rows.get(n);}
    /** Retrieves a bureau resource through the public API. */
    private JsonNode get(String path){return api.call("GET","bureau/"+path,null);}
    /** Renders bureau workflow screens with explicit guidance for locating the generated customer UUID. */
    String draw(TerminalServer.Screen screen){if(view.equals("OFFERS"))return offers.draw(screen);if(view.equals("DECISIONS"))return responses.draw(screen);screen.text(3,1,"BUREAU QUALIFICATION | "+view+" | LOCAL SIMULATION",YELLOW);String footer="ENTER=Continue  F3=Bureau menu";
        switch(view){
            case "HOME"->{screen.text(5,6,"1  Qualify a finalized marketing run",BLUE);screen.text(7,6,"2  Review bureau requests / credit decisions",BLUE);screen.text(9,6,"3  Review durable batches",BLUE);screen.text(11,6,"4  Generate / edit an independent bureau profile",BLUE);screen.text(13,6,"5  Database and Kafka status",BLUE);screen.text(15,6,"6  Decision responses / customer eligible offers (Step 4)",CYAN);screen.text(17,6,"7  Personalized offers / customer choices / models (Step 5)",CYAN);screen.field("choice",19,20,1,"","Selection");footer="ENTER=Select  F3=Main menu";}
            case "RUNS"->{rows=api.call("GET","marketing/runs?offset="+offset+"&limit=10",null).path("items");int i=0;for(var r:rows)screen.text(6+i,1,(++i)+" "+r.path("id").asText()+" "+r.path("status").asText(),BLUE);screen.field("choice",18,20,2,"","Run row");footer="ENTER=Open finalized run  F7/F8=Page  F3=Menu";}
            case "SOURCES"->{screen.text(4,1,"Run: "+runId,WHITE);rows=get("sources/"+runId+"?after="+sourceAfter+"&limit=10");int i=0;for(var r:rows){var q=r.path("qualification");screen.text(6+i,1,(++i)+" "+q.path("customerId").asText()+" "+q.path("outcome").asText()+" "+q.path("product").asText(),BLUE);}screen.field("choice",18,20,2,"","Row or B=batch");footer="ENTER=Select / submit entire run  F7/F8=Page  F3=Menu";}
            case "QUALIFICATION"->{screen.text(5,1,"Customer: "+customerId,BLUE);screen.text(7,1,"Risk ID: "+qualification.path("riskId").asText(),CYAN);screen.text(9,1,"Product: "+qualification.path("product").asText()+"  "+qualification.path("outcome").asText(),WHITE);screen.text(11,1,"Offer: "+qualification.path("offerId").asText(),BLUE);screen.text(14,1,"Q=Submit credit qualification   P=Generate/edit bureau profile",WHITE);screen.field("action",18,20,1,"Q","Action");}
            case "REQUESTS","BATCHES","ITEMS"->{rows=get(view.equals("ITEMS")?"batches/"+batchId+"/items?after="+after+"&limit=10":(view.equals("BATCHES")?"batches":"requests")+"?after="+after+"&limit=10");int i=0;for(var r:rows)screen.text(6+i,1,(++i)+" "+r.path("requestId").asText()+" "+r.path("status").asText(),BLUE);screen.field("choice",18,20,2,"","Row");footer="ENTER=Open  F7/F8=Page  F3=Menu";}
            case "REQUEST"->{selected=get("requests/"+requestId);var result=selected.path("result");var decision=result.path("decision");screen.text(5,1,"Request: "+requestId,BLUE);screen.text(7,1,"Status: "+selected.path("status").asText()+"  Attempts: "+selected.path("attempts").asText(),YELLOW);screen.text(9,1,"Risk ID: "+result.path("riskId").asText(),CYAN);screen.text(11,1,"Credit outcome: "+decision.path("outcome").asText("pending / unavailable"),CYAN);screen.text(12,1,"Policy: "+decision.path("policyVersion").asText()+"  Bureau profile v"+decision.path("bureauProfileVersion").asText(),BLUE);screen.text(13,1,"Assessed: "+decision.path("assessedAt").asText(),WHITE);screen.text(14,1,"Valid until: "+decision.path("validUntil").asText(),WHITE);
                var reasons=new ArrayList<String>();if(!result.path("reason").isNull()&&!result.path("reason").isMissingNode())reasons.add(result.path("reason").asText());for(var r:decision.path("reasons"))reasons.add(r.asText());String text=String.join(", ",reasons);for(int n=0;n<5&&n*76<text.length();n++)screen.text(16+n,1,text.substring(n*76,Math.min((n+1)*76,text.length())),BLUE);footer="ENTER=Refresh  F3=Menu | Historical decision; no loan is issued";}
            case "BATCH"->{selected=get("batches/"+batchId);screen.text(5,1,"Batch: "+batchId,BLUE);screen.text(7,1,"Status: "+selected.path("status").asText(),YELLOW);screen.text(9,1,"Expanded: "+selected.path("expanded").asText()+"  Run index: "+selected.path("runIndex").asText(),WHITE);int row=11;var fields=selected.path("counts").fields();while(fields.hasNext()){var f=fields.next();screen.text(row++,1,f.getKey()+": "+f.getValue().asText(),CYAN);}screen.text(17,1,selected.path("error").asText(""),WHITE);screen.field("action",19,20,1,"","I=items / refresh");}
            case "PROFILE-ID"->{screen.text(7,1,"Enter the generated Step 1 Customer ID (36-character UUID).",WHITE);screen.text(8,1,"External ref values such as DEMO-CUST-001 are not Customer IDs.",YELLOW);screen.text(9,1,"Find the ID in Customer > 02 Open a demo customer profile.",CYAN);screen.text(11,1,"Missing bureau profiles are generated independently of self-report.",BLUE);screen.field("customerId",13,16,40,"","Customer ID");}
            case "PROFILE"->{screen.text(4,1,"Customer: "+customerId,BLUE);screen.text(5,1,"Bureau v"+selected.path("version").asText()+" "+selected.path("source").asText(),CYAN);var facts=selected.path("facts");String[][] fields={{"fileStatus","File status","12"},{"creditScore","Bureau score","3"},{"monthlyDebtUsd","Bureau debt USD/mo","12"},{"utilizationPct","Utilization %","3"},{"delinquencies12m","Delinquencies 12m","2"},{"inquiries6m","Inquiries 6m","2"},{"oldestAccountMonths","Oldest account mo","4"},{"bankruptcy","Bankruptcy Y/N","1"},{"reportedAt","Reported UTC","30"}};
                for(int i=0;i<fields.length;i++){var f=fields[i];var n=facts.path(f[0]);String value=n.isNull()?"":f[0].equals("bankruptcy")?(n.asBoolean()?"Y":"N"):n.asText();screen.field(f[0],7+i,25,Integer.parseInt(f[2]),value,f[1]);}screen.text(18,1,"MATCHED / NO_HIT / FROZEN / AMBIGUOUS. Blank facts remain unknown.",WHITE);screen.text(19,1,"Saves a new LOCAL_EDIT version. Prior decisions remain historical.",WHITE);footer="ENTER=Save bureau version  F3=Cancel";}
            case "PROFILE-SAVED"->{screen.text(8,1,"Bureau profile v"+selected.path("version").asText()+" saved.",CYAN);screen.text(11,1,"Select a current qualification to request a new credit assessment.",WHITE);}
            case "HEALTH"->{selected=get("health");int row=6;var fields=selected.fields();while(fields.hasNext()){var field=fields.next();screen.text(row++,1,field.getKey()+": "+field.getValue().asText(),CYAN);}footer="ENTER=Refresh  F3=Menu";}
        }return footer;
    }
}

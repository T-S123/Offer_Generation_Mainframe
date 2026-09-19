/**
 * Business Step 5 operations over HTTP: legacy-batch training, financial comparisons, aggregate metrics
 * and current customer choices.
 */
package com.lending.engine.terminal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * Business Step 5 operations over HTTP: legacy-batch training, financial comparisons, aggregate metrics
 * and current customer choices.
 */
final class OffersTerminal {
    private static final int BLUE=0xf1,CYAN=0xf5,YELLOW=0xf6,WHITE=0xf7;
    private final ApiClient api;private String view="HOME",after="",next,customerId,notice="";private JsonNode rows,selected;private boolean customer;private final Deque<String> previous=new ArrayDeque<>();
    /** Initializes offers terminal with the supplied configuration and dependencies. */
    OffersTerminal(ApiClient api){this.api=api;}
    /** Clears the screen state before beginning a new terminal interaction. */
    void reset(){view="HOME";after="";previous.clear();notice="";}
    /**
     * Processes terminal keys and submitted fields for the active screen, issuing commands through HTTP
     * APIs.
     */
    boolean handle(int aid,Map<String,String> values){if(aid==0xf3){if(view.equals("HOME"))return true;reset();return false;}if(view.equals("LIST")&&(aid==0xf7||aid==0xf8)){if(aid==0xf8&&next!=null){previous.push(after);after=next;}else if(aid==0xf7&&!previous.isEmpty())after=previous.pop();return false;}if(aid!=0x7d)return false;
        String choice=values.getOrDefault("choice","").trim();switch(view){
            case "HOME"->{switch(choice){case "1"->view="TRAIN";case "2"->view="CUSTOMER";case "3"->{customer=false;view="LIST";}case "4"->view="MODELS";case "5"->view="HEALTH";case "6"->view="METRICS";default->throw new IllegalArgumentException("Choose 1-6");}}
            case "TRAIN"->{String batch=values.getOrDefault("batchId","").trim();long seed=Long.parseLong(values.getOrDefault("seed","42").trim());selected=call("POST","training",Map.of("batchId",batch,"seed",seed));notice="Training queued: "+selected.path("id").asText();view="MODELS";}
            case "CUSTOMER"->{customerId=values.getOrDefault("customerId","").trim();api.call("GET","customers/"+customerId,null);customer=true;view="LIST";}
            case "LIST"->{int i=Integer.parseInt(choice)-1;if(i<0||i>=rows.size())throw new IllegalArgumentException("Choose a displayed row");selected=rows.get(i);view="DETAIL";}
            case "DETAIL"->{if(customer){String kind=switch(choice.toUpperCase(Locale.ROOT)){case "O"->"ORIGINAL";case "P"->"PERSONALIZED";default->throw new IllegalArgumentException("O=original or P=personalized");};var result=call("POST","offers/"+selected.path("id").asText()+"/selections",Map.of("requestId",UUID.randomUUID().toString(),"sourceVersion",selected.path("sourceVersion").asLong(),"kind",kind));notice="Selected "+result.path("kind").asText()+". Demo only; no loan issued.";}else if(choice.equalsIgnoreCase("H")){rows=call("GET","offers/"+selected.path("id").asText()+"/history",null);view="HISTORY";}}
        }return false;
    }
    /** Calls the marketing-service proxy through the public engine API. */
    private JsonNode call(String method,String path,Object body){return api.call(method,"offer-creation/"+path,body);}
    /** Renders the current workflow state and input fields on the terminal screen. */
    String draw(TerminalServer.Screen s){s.text(3,1,"MARKETING OFFERS | "+view+" | LOCAL SIMULATION",YELLOW);String footer="ENTER=Refresh  F3=Offer menu";
        switch(view){
            case "HOME"->{s.text(6,5,"1  Train cohorts from a legacy Step 1 simulation",BLUE);s.text(8,5,"2  Customer: compare / choose original or personalized",CYAN);s.text(10,5,"3  Business: created offers and comparison metrics",BLUE);s.text(12,5,"4  Training jobs / model quality",BLUE);s.text(14,5,"5  Offer service / Kafka / storage status",BLUE);s.text(16,5,"6  Business: aggregate simulation metrics",BLUE);s.field("choice",19,20,1,"","Selection");footer="ENTER=Select  F3=Bureau menu";}
            case "TRAIN"->{s.text(6,1,"Use the Step 1 simulation batch ID (60-10000 customers).",WHITE);s.text(8,1,"Local k-means; held-out evaluation; no external AI calls.",CYAN);s.text(10,1,"Steps 2-4 must qualify customers before any offer activates.",WHITE);s.field("batchId",13,16,40,"","Batch ID");s.field("seed",15,16,10,"42","Model seed");footer="ENTER=Train asynchronously  F3=Cancel";}
            case "CUSTOMER"->{s.field("customerId",11,16,40,"","Customer ID");s.text(14,1,"Only currently eligible choices are displayed.",CYAN);}
            case "LIST"->{var page=call("GET",(customer?"customers/"+customerId+"/offers":"offers")+"?limit=8&after="+after,null);rows=page.path("items");next=page.path("nextAfter").isNull()?null:page.path("nextAfter").asText();int i=0;for(var o:rows){s.text(6+i,1,(++i)+" "+o.path("catalogOfferId").asText()+" "+o.path("status").asText()+" "+o.path("personalizationStatus").asText(),BLUE);}if(rows.isEmpty())s.text(8,1,"No offers here. F8 continues when more pages exist.",CYAN);s.text(16,1,customer?"Choose a row to compare financial terms.":"Business audit includes historical/revoked offers; no outbound mail sent.",WHITE);s.field("choice",18,20,1,"","Offer row");footer="ENTER=Compare  F7/F8=Page  F3=Menu";}
            case "DETAIL"->{if(customer)selected=call("GET","offers/"+selected.path("id").asText(),null);JsonNode original=selected.path("original"),personalized=selected.path("personalized");s.text(4,1,"Customer: "+selected.path("customerId").asText(),BLUE);s.text(5,1,selected.path("catalogOfferId").asText()+" v"+selected.path("catalogOfferVersion").asText()+" "+selected.path("product").asText()+" "+selected.path("status").asText(),CYAN);s.text(6,1,"USD / metric                 ORIGINAL           PERSONALIZED",YELLOW);
                String[][] fields={{"amountUsd","Amount / limit"},{"aprPct","APR %"},{"annualFeeUsd","Annual fee"},{"termMonths","Months (cards=0)"}};int row=7;for(var f:fields){s.text(row,1,f[1],WHITE);s.text(row,30,original.path("terms").path(f[0]).asText(),BLUE);s.text(row++,50,personalized.isNull()?"--":personalized.path("terms").path(f[0]).asText(),CYAN);}
                String[][] metrics={{"fitScore","Customer fit /100"},{"simulatedAcceptance","Sim acceptance 0-1"},{"monthlyPaymentUsd","Monthly payment"},{"totalCostUsd","Borrowing cost"}};for(var m:metrics){s.text(row,1,m[1],WHITE);s.text(row,30,number(original.path("metrics").path(m[0])),BLUE);s.text(row++,50,personalized.isNull()?"--":number(personalized.path("metrics").path(m[0])),CYAN);}s.text(15,1,"Status: "+selected.path("personalizationStatus").asText()+"  Cohort: "+selected.path("cohort").asText(),WHITE);s.text(16,1,"Acceptance is simulated, NOT measured. Card cost = 1 year full balance.",WHITE);s.text(17,1,"Model: "+selected.path("modelId").asText("waiting for training"),BLUE);s.text(18,1,notice,CYAN);s.field("choice",20,24,1,"",customer?"O=original P=personal":"H=history / refresh");footer=customer?"ENTER=Select choice (demo only)  F3=Menu":"ENTER=History / refresh  F3=Menu";}
            case "MODELS"->{var jobs=call("GET","training",null);s.text(5,1,notice,CYAN);int i=0;for(var job:jobs){if(i==4)break;s.text(7+i++,1,job.path("id").asText()+" "+job.path("status").asText(),BLUE);}var models=call("GET","models",null);if(!models.isEmpty()){var m=models.get(0);var metrics=m.path("metrics");s.text(13,1,"Latest model "+m.path("id").asText(),CYAN);s.text(14,1,"Train / validation / test: "+metrics.path("trainingCount").asInt()+" / "+metrics.path("validationCount").asInt()+" / "+metrics.path("testCount").asInt(),WHITE);s.text(15,1,"Cohorts "+metrics.path("cohortCount").asInt()+"   Validation silhouette "+number(metrics.path("validationSilhouette")),WHITE);s.text(16,1,"Test silhouette "+number(metrics.path("testSilhouette"))+"  Observed acceptance labels: 0",WHITE);}s.field("refresh",19,20,1,"","ENTER=Refresh");}
            case "HEALTH"->{var h=call("GET","health",null);int row=5;var fields=h.fields();while(fields.hasNext()&&row<20){var f=fields.next();s.text(row++,1,f.getKey()+": "+f.getValue().asText(),CYAN);}s.field("refresh",20,20,1,"","ENTER=Refresh");}
            case "METRICS"->{var groups=call("GET","metrics",null).path("groups");s.text(5,1,"PRODUCT       N / PERSONALIZED  FIT+   ACCEPT+pp  PAYMENT$+  COST$+",YELLOW);int row=7;for(var g:groups){if(row>=17)break;s.text(row++,1,g.path("product").asText()+" "+g.path("activeOfferFamilies").asText()+"/"+g.path("personalizedFamilies").asText()+"  "+number(g.path("meanFitUplift"))+"  "+number(g.path("meanSimulatedAcceptanceUpliftPp"))+"  "+number(g.path("meanPaymentChangeUsd"))+"  "+number(g.path("meanCostChangeUsd")),CYAN);}s.text(17,1,"Grouped by product/model. Stored state may lag eligibility.",WHITE);s.text(18,1,"Acceptance simulated; fairness / real acceptance not established.",WHITE);s.field("refresh",20,20,1,"","ENTER=Refresh");}
            case "HISTORY"->{int i=0;for(var h:rows){if(i==12)break;var o=h.path("offer");s.text(6+i++,1,"#"+h.path("sequence").asText()+" v"+o.path("sourceVersion").asText()+" "+o.path("status").asText()+" "+o.path("personalizationStatus").asText(),CYAN);}s.field("refresh",20,20,1,"","ENTER=Refresh");}
        }return footer;
    }
    /** Formats a numeric comparison metric to three decimals or displays a missing-value marker. */
    private static String number(JsonNode n){return n.isNumber()?String.format(Locale.ROOT,"%.3f",n.asDouble()):"--";}
}

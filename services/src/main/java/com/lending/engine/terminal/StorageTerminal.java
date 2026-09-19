/**
 * Business operations over HTTP: Step 6 screens: eligible customer choices, authoritative storage,
 * independent warehouse history and copy health.
 */
package com.lending.engine.terminal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * Business operations over HTTP: Step 6 screens: eligible customer choices, authoritative storage,
 * independent warehouse history and copy health.
 */
final class StorageTerminal {
    private static final int BLUE=0xf1,CYAN=0xf5,YELLOW=0xf6,WHITE=0xf7;
    private final ApiClient api;private String view="HOME",mode="STORE",customerId,after="",next,notice="";private JsonNode rows,selected;private final Deque<String> previous=new ArrayDeque<>();
    /** Initializes storage terminal with the supplied configuration and dependencies. */
    StorageTerminal(ApiClient api){this.api=api;}
    /** Clears the screen state before beginning a new terminal interaction. */
    void reset(){view="HOME";after="";previous.clear();notice="";}
    /** Sets the customer context for inspecting eligible stored offers. */
    void customer(String id){reset();customerId=id;mode="CUSTOMER";view="LIST";}
    /** Returns the API path prefix for the selected repository or warehouse view. */
    private String prefix(){return mode.equals("WAREHOUSE")?"warehouse/offers":"offer-creation/storage/offers";}
    /**
     * Processes terminal keys and submitted fields for the active screen, issuing commands through HTTP
     * APIs.
     */
    boolean handle(int aid,Map<String,String> values){
        if(aid==0xf3){if(view.equals("HOME"))return true;if(view.equals("DETAIL")||view.equals("HISTORY")){view="LIST";after="";previous.clear();}else reset();return false;}
        if((view.equals("LIST")||view.equals("HISTORY"))&&(aid==0xf7||aid==0xf8)){if(aid==0xf8&&next!=null){previous.push(after);after=next;}else if(aid==0xf7&&!previous.isEmpty())after=previous.pop();return false;}
        if(aid!=0x7d)return false;String choice=values.getOrDefault("choice","").trim();
        switch(view){
            case "HOME"->{switch(choice){case "1"->{mode="STORE";view="LIST";}case "2"->{mode="WAREHOUSE";view="LIST";}case "3"->view="CUSTOMER";case "4"->view="HEALTH";default->throw new IllegalArgumentException("Choose 1-4");}}
            case "CUSTOMER"->{String id=values.getOrDefault("customerId","").trim();api.call("GET","customers/"+id,null);customer(id);}
            case "LIST"->{int i=Integer.parseInt(choice)-1;if(i<0||i>=rows.size())throw new IllegalArgumentException("Choose a displayed row");selected=rows.get(i);if(mode.equals("WAREHOUSE"))selected=selected.path("offer");view="DETAIL";}
            case "DETAIL"->{if(mode.equals("CUSTOMER")){if(choice.isBlank())break;String kind=switch(choice.toUpperCase(Locale.ROOT)){case "O"->"ORIGINAL";case "P"->"PERSONALIZED";default->throw new IllegalArgumentException("O=original P=personalized");};var result=api.call("POST","offer-creation/offers/"+selected.path("id").asText()+"/selections",Map.of("requestId",UUID.randomUUID().toString(),"sourceVersion",selected.path("qualification").path("responseVersion").asLong(),"kind",kind));notice="Selected "+result.path("kind").asText()+"; copy updates automatically.";}else if(choice.equalsIgnoreCase("H")){view="HISTORY";after="0";previous.clear();}}
        }return false;
    }
    /** Renders the current workflow state and input fields on the terminal screen. */
    String draw(TerminalServer.Screen s){s.text(3,1,"OFFER STORAGE | "+mode+" | "+view,YELLOW);String footer="ENTER=Refresh  F3=Back";
        switch(view){
            case "HOME"->{s.text(6,4,"1  Business: pre-screen and marketing offer storage",BLUE);s.text(8,4,"2  Business: enterprise warehouse copies / history",BLUE);s.text(10,4,"3  Customer: currently eligible offers / choice",CYAN);s.text(12,4,"4  Storage publication / customer and warehouse loaders",BLUE);s.text(16,1,"Pre-screen = original catalog; marketing = personalized Step 5.",WHITE);s.field("choice",19,20,1,"","Selection");footer="ENTER=Select  F3=Main menu";}
            case "CUSTOMER"->s.field("customerId",10,16,40,"","Customer ID");
            case "LIST"->{var page=api.call("GET",(mode.equals("CUSTOMER")?"customers/"+customerId+"/offers":prefix())+"?limit=8&after="+after,null);rows=page.path("items");next=page.path("nextAfter").isNull()?null:page.path("nextAfter").asText();int i=0;for(var value:rows){var o=mode.equals("WAREHOUSE")?value.path("offer"):value;s.text(6+i,1,(++i)+" "+o.path("qualification").path("catalogOfferId").asText()+" v"+o.path("version").asText()+" PRE:"+o.path("preScreen").path("status").asText("--")+" MKT:"+o.path("marketing").path("status").asText("--"),CYAN);}if(rows.isEmpty())s.text(8,1,"No offers on this page; F8 continues if more pages exist.",WHITE);s.text(16,1,mode.equals("CUSTOMER")?"Eligibility checked now. Offer/selection copies may briefly lag.":"Business history includes hidden, declined, expired and withdrawn offers.",WHITE);s.field("choice",19,20,1,"","Offer row");footer="ENTER=View  F7/F8=Page  F3=Back";}
            case "DETAIL"->{if(mode.equals("CUSTOMER")){selected=api.call("GET","customers/"+customerId+"/offers/"+selected.path("id").asText(),null);}else selected=api.call("GET",prefix()+"/"+selected.path("id").asText(),null).path("offer");
                s.text(4,1,"Customer "+selected.path("customerId").asText(),BLUE);s.text(5,1,selected.path("product").asText()+" | storage v"+selected.path("version").asText()+" | catalog v"+selected.path("qualification").path("catalogOfferVersion").asText(),WHITE);s.text(6,1,"USD / metric                    PRE-SCREEN         MARKETING",YELLOW);int row=7;for(String key:List.of("amountUsd","aprPct","annualFeeUsd","termMonths")){s.text(row,1,key,WHITE);s.text(row,32,value(selected.path("preScreen").path("terms").path(key)),BLUE);s.text(row++,51,value(selected.path("marketing").path("terms").path(key)),CYAN);}for(String key:List.of("fitScore","simulatedAcceptance","monthlyPaymentUsd","totalCostUsd")){s.text(row,1,key,WHITE);s.text(row,32,value(selected.path("preScreen").path("metrics").path(key)),BLUE);s.text(row++,51,value(selected.path("marketing").path("metrics").path(key)),CYAN);}
                s.text(15,1,"PRE "+selected.path("preScreen").path("status").asText("--")+"  MKT "+selected.path("marketing").path("status").asText("--"),BLUE);s.text(16,1,"Selected: "+selected.path("selection").path("kind").asText("none")+"  Available: "+selected.path("selection").path("available").asText("--"),CYAN);s.text(17,1,"Acceptance is simulated; selection issues no loan or campaign message.",WHITE);s.text(18,1,notice,CYAN);s.field("choice",20,25,1,"",mode.equals("CUSTOMER")?"O=original P=personal":"H=history / refresh");}
            case "HISTORY"->{rows=api.call("GET",prefix()+"/"+selected.path("id").asText()+"/history?after="+after,null);int i=0;for(var event:rows){if(i==12)break;var o=event.path("offer");s.text(6+i++,1,"v"+o.path("version").asText()+" "+event.path("occurredAt").asText()+" PRE:"+o.path("preScreen").path("status").asText()+" MKT:"+o.path("marketing").path("status").asText("--"),CYAN);}next=rows.size()>12?rows.get(11).path("offer").path("version").asText():null;s.field("refresh",20,20,1,"","Refresh");footer="ENTER=Refresh  F7/F8=History page  F3=List";}
            case "HEALTH"->{var stored=api.call("GET","offer-creation/storage/health",null);s.text(6,1,"Publisher: "+stored.path("publisher").asText()+"  Scanner: "+stored.path("prescreenScanner").asText(),CYAN);s.text(8,1,"Stored families: "+stored.path("storedFamilies").asText()+"  Events: "+stored.path("events").asText()+"  Pending: "+stored.path("pendingPublication").asText(),WHITE);var h=api.call("GET","offer-copies/health",null);int row=11;for(String copy:List.of("customer","warehouse")){var c=h.path(copy);s.text(row++,1,copy+" Kafka: "+c.path("kafka").asText()+" HTTP: "+c.path("httpRecovery").asText(),CYAN);s.text(row++,1,"Families: "+c.path("families").asText()+" Events: "+c.path("historyEvents").asText()+" Cursor: "+c.path("recoveryAfter").asText(),BLUE);}s.field("refresh",20,20,1,"","Refresh");}
        }return footer;
    }
    /** Formats a numeric stored-offer value to two decimals or displays a missing-value marker. */
    private static String value(JsonNode n){return n.isNumber()?String.format(Locale.ROOT,"%.2f",n.asDouble()):"--";}
}

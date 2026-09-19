/**
 * Step 4 operator audit, delivery replay and customer eligibility screens use only the public HTTP
 * contracts.
 */
package com.lending.engine.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * Step 4 operator audit, delivery replay and customer eligibility screens use only the public HTTP
 * contracts.
 */
final class ResponseTerminal {
    private static final int BLUE=0xf1,CYAN=0xf5,YELLOW=0xf6,WHITE=0xf7;
    private final ApiClient api;private String view="HOME",after="",customerId,responseId,nextAfter,notice="";private JsonNode rows,selected;
    private final Deque<String> previous=new ArrayDeque<>();
    /** Initializes response terminal with the supplied configuration and dependencies. */
    ResponseTerminal(ApiClient api){this.api=api;}
    /** Clears the screen state before beginning a new terminal interaction. */
    void reset(){view="HOME";after="";previous.clear();notice="";}
    /**
     * Processes terminal keys and submitted fields for the active screen, issuing commands through HTTP
     * APIs.
     */
    boolean handle(int aid,Map<String,String> values){
        if(aid==0xf3){if(view.equals("HOME"))return true;reset();return false;}
        if((aid==0xf7||aid==0xf8)&&Set.of("RESPONSES","OFFERS","HISTORY").contains(view)){
            if(aid==0xf8&&nextAfter!=null){previous.push(after);after=nextAfter;}
            else if(aid==0xf7&&!previous.isEmpty())after=previous.pop();return false;
        }
        if(aid!=0x7d)return false;
        switch(view){
            case "HOME"->{switch(values.getOrDefault("choice","").trim()){
                case "1"->view="RESPONSES";case "2"->view="CUSTOMER";case "3"->view="HEALTH";
                default->throw new IllegalArgumentException("Choose 1-3");}}
            case "CUSTOMER"->{customerId=values.getOrDefault("customerId","").trim();api.call("GET","customers/"+customerId,null);view="OFFERS";}
            case "RESPONSES"->{int index;try{index=Integer.parseInt(values.getOrDefault("choice","").trim())-1;}catch(Exception e){throw new IllegalArgumentException("Choose a displayed response row");}
                if(index<0||index>=rows.size())throw new IllegalArgumentException("Choose a displayed response row");responseId=rows.get(index).path("id").asText();view="DETAIL";}
            case "DETAIL"->{String action=values.getOrDefault("action","").trim();if(action.equalsIgnoreCase("H")){after="0";previous.clear();view="HISTORY";}
                else if(action.equalsIgnoreCase("R")){var replay=api.call("POST","decision-responses/"+responseId+"/replay",Map.of());notice="Queued current version "+replay.path("version").asText()+" with its existing event ID";}}
        }
        return false;
    }
    /** Renders the current workflow state and input fields on the terminal screen. */
    String draw(TerminalServer.Screen screen){screen.text(3,1,"DECISION RESPONSE | "+view+" | LOCAL SIMULATION",YELLOW);String footer="ENTER=Refresh  F3=Decision response menu";
        switch(view){
            case "HOME"->{screen.text(7,5,"1  Operator audit: responses, history and publication replay",BLUE);screen.text(10,5,"2  Customer view: currently qualified catalog offers only",BLUE);screen.text(13,5,"3  Response processing and publication status",BLUE);screen.field("choice",18,20,1,"","Selection");footer="ENTER=Select  F3=Bureau menu";}
            case "CUSTOMER"->{screen.text(7,1,"Enter the customer to view their eligible catalog offers.",WHITE);screen.text(9,1,"Approved bureau decision and current Step 2 qualification required.",CYAN);screen.field("customerId",13,16,40,"","Customer ID");footer="ENTER=View eligible offers  F3=Menu";}
            case "RESPONSES","OFFERS"->{boolean customer=view.equals("OFFERS");selected=api.call("GET",customer?"customers/"+customerId+"/eligible-offers?after="+after+"&limit=8":"decision-responses?after="+after+"&limit=8",null);rows=selected.path("items");nextAfter=selected.path("nextAfter").isNull()?null:selected.path("nextAfter").asText();
                screen.text(5,1,customer?"Customer: "+customerId:"OPERATOR AUDIT - includes declined and revoked responses",WHITE);int i=0;
                for(var r:rows){String line=customer?r.path("qualifiedOffer").path("id").asText()+" v"+r.path("qualifiedOffer").path("version").asText()+"  "+r.path("qualification").path("product").asText()+" USD "+r.path("assessedAmountUsd").asText():r.path("customerId").asText()+" "+r.path("status").asText()+" v"+r.path("version").asText();screen.text(7+i,1,(++i)+" "+line,BLUE);}
                if(rows.isEmpty())screen.text(8,1,customer?"No eligible offers on this page. F8 continues if a cursor exists.":"No decision responses on this page.",CYAN);
                if(!customer)screen.field("choice",18,20,1,"","Response row");else{screen.text(17,1,"Qualification only. No personalized offer has been created.",WHITE);screen.field("refresh",19,20,1,"","ENTER=Recheck");}
                footer=customer?"ENTER=Recheck eligibility  F7/F8=Page  F3=Menu":"ENTER=Open audit  F7/F8=Page  F3=Menu";}
            case "DETAIL"->{selected=api.call("GET","decision-responses/"+responseId,null);screen.text(5,1,"Customer: "+selected.path("customerId").asText(),BLUE);screen.text(6,1,"Risk ID: "+selected.path("source").path("riskId").asText(),CYAN);screen.text(8,1,"Version: "+selected.path("version").asText()+"  Status: "+selected.path("status").asText(),YELLOW);screen.text(9,1,"Marketing eligible: "+selected.path("marketingEligible").asText(),CYAN);screen.text(11,1,"Catalog: "+selected.path("qualifiedOffer").path("id").asText()+" v"+selected.path("qualifiedOffer").path("version").asText(),BLUE);screen.text(12,1,"Bureau outcome: "+selected.path("bureauDecision").path("outcome").asText("unavailable"),BLUE);screen.text(13,1,"Valid until (exclusive): "+selected.path("validUntil").asText(),WHITE);screen.text(15,1,selected.path("reasons").toString(),WHITE);screen.text(17,1,notice,CYAN);screen.field("action",19,18,1,"","H=history R=replay");}
            case "HISTORY"->{rows=api.call("GET","decision-responses/"+responseId+"/history?after="+(after.isEmpty()?"0":after)+"&limit=8",null);int i=0;for(var r:rows)screen.text(7+i,1,"v"+r.path("response").path("version").asText()+" "+r.path("response").path("status").asText()+"  "+r.path("deliveryStatus").asText()+" tries="+r.path("attempts").asText()+" replays="+r.path("replayCount").asText(),++i%2==0?CYAN:BLUE);nextAfter=rows.size()==8?rows.get(7).path("response").path("version").asText():null;screen.text(18,1,"PUBLISHED = Kafka acknowledged. Downstream consumption is separate.",WHITE);footer="ENTER=Refresh delivery  F7/F8=Page  F3=Menu";}
            case "HEALTH"->{selected=api.call("GET","decision-responses/health",null);int row=6;var fields=selected.fields();while(fields.hasNext()){var f=fields.next();screen.text(row++,1,f.getKey()+": "+f.getValue().asText(),CYAN);}}
        }return footer;
    }
}

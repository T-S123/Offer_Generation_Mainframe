/** Step 2 terminal presentation: catalog forms and review/finalize commands use HTTP exclusively. */
package com.lending.engine.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lending.engine.infrastructure.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** Step 2 terminal presentation: catalog forms and review/finalize commands use HTTP exclusively. */
final class MarketingTerminal {
    private static final int BLUE=0xf1,CYAN=0xf5,YELLOW=0xf6,WHITE=0xf7;
    private final ApiClient api;
    private String view="HOME",kind,runId,populationId,requestId;
    private int offset,reasonOffset;
    private JsonNode rows,selected;
    /** Initializes marketing terminal with the supplied configuration and dependencies. */
    MarketingTerminal(ApiClient api){this.api=api;}
    /** Clears the screen state before beginning a new terminal interaction. */
    void reset(){view="HOME";offset=0;}
    /**
     * Processes terminal keys and submitted fields for the active screen, issuing commands through HTTP
     * APIs.
     */
    boolean handle(int aid,Map<String,String> values){
        if(aid==0xf3){if(view.equals("HOME"))return true;view="HOME";offset=0;return false;}
        if(aid==0xf7 || aid==0xf8){
            if(view.equals("RESULT"))reasonOffset=Math.max(0,reasonOffset+(aid==0xf8?8:-8));
            else if(Set.of("CATALOG","SOURCES","RUNS","RESULTS").contains(view))offset=Math.max(0,offset+(aid==0xf8?10:-10));
            return false;
        }
        if(aid!=0x7d)return false;
        switch(view){
            case "HOME"->{offset=0;switch(value(values,"choice")){
                case "1"->{kind="offers";view="CATALOG";}case "2"->{kind="campaigns";view="CATALOG";}
                case "3"->{selected=get("policy");view="POLICY";}case "4"->{kind="suppressions";view="CATALOG";}
                case "5"->view="SOURCES";case "6"->view="RUNS";default->throw new IllegalArgumentException("Choose 1-6");}}
            case "CATALOG"->{String id=value(values,"id");
                if(id.isEmpty())selected=pick(rows,value(values,"choice"),true);
                else{selected=null;for(JsonNode r:rows)if(r.path(kind.equals("suppressions")?"customerId":"id").asText().equals(id)){selected=r;break;}
                    if(selected==null)selected=template(id);}
                checkEditable();view=kind.equals("offers")?"OFFER":kind.equals("campaigns")?"CAMPAIGN":"SUPPRESSION";}
            case "OFFER"->{var data=Json.MAPPER.createObjectNode();data.put("name",value(values,"name"));data.put("product",product(value(values,"product")));data.put("active",yes(values,"active"));
                data.put("startsOn",value(values,"startsOn"));data.put("endsOn",value(values,"endsOn"));
                for(String key:List.of("minimumAmountUsd","maximumAmountUsd","illustrativeAprPct","annualFeeUsd"))data.put(key,new BigDecimal(value(values,key)));
                data.put("termMonths",Integer.parseInt(value(values,"termMonths")));save("offers","offer",data);}
            case "CAMPAIGN"->{var data=Json.MAPPER.createObjectNode();data.put("name",value(values,"name"));data.put("active",yes(values,"active"));
                data.put("startsOn",value(values,"startsOn"));data.put("endsOn",value(values,"endsOn"));
                for(String key:List.of("priority","capacity","minimumTenureMonths"))data.put(key,Integer.parseInt(value(values,key)));
                data.put("excludeExistingProduct",yes(values,"excludeExistingProduct"));data.set("offerIds",Json.MAPPER.valueToTree(csv(value(values,"offerIds"))));save("campaigns","campaign",data);}
            case "SUPPRESSION"->{var data=Json.MAPPER.createObjectNode();data.put("active",yes(values,"active"));data.put("reason",value(values,"reason"));
                if(value(values,"expiresOn").isEmpty())data.putNull("expiresOn");else data.put("expiresOn",value(values,"expiresOn"));
                api.call("PUT","marketing/suppressions/"+selected.path("customerId").asText(),Map.of("expectedVersion",selected.path("version").asInt(),"suppression",data));view="CATALOG";}
            case "POLICY"->{var data=Json.MAPPER.createObjectNode();for(String key:List.of("cooldownDays","rollingWindowDays","maximumReservations","reservationDays"))data.put(key,Integer.parseInt(value(values,key)));
                selected=api.call("PUT","marketing/policy",Map.of("expectedVersion",selected.path("version").asInt(),"policy",data));view="HOME";}
            case "SOURCES"->{selected=pick(rows,value(values,"choice"),true);populationId=selected.path("id").asText();requestId=UUID.randomUUID().toString();view="PREVIEW";}
            case "PREVIEW"->{selected=api.call("POST","marketing/runs",Map.of("requestId",requestId,"populationId",populationId,"campaignIds",csv(value(values,"campaigns"))));runId=selected.path("id").asText();view="RUN";}
            case "RUNS"->{selected=pick(rows,value(values,"choice"),false);runId=selected.path("id").asText();view="RUN";}
            case "RUN"->{switch(value(values,"action").toUpperCase(Locale.ROOT)){
                case "R"->{offset=0;view="RESULTS";}case "F"->selected=api.call("POST","marketing/runs/"+runId+"/finalize",Map.of());
                case "C"->selected=api.call("POST","marketing/runs/"+runId+"/cancel",Map.of());case ""->{}default->throw new IllegalArgumentException("R=results, F=finalize, C=cancel and release");}}
            case "RESULTS"->{selected=pick(rows,value(values,"choice"),false);reasonOffset=0;view="RESULT";}
            case "RESULT"->view="RESULTS";
        }
        return false;
    }
    /** Renders the current workflow state and input fields on the terminal screen. */
    String draw(TerminalServer.Screen s){
        s.text(3,1,"DMG MARKETING | "+view+" | Demonstration policies",YELLOW);
        String footer="ENTER=Continue  F3=Marketing menu";
        switch(view){
            case "HOME"->{s.text(5,6,"1  Offer catalog - create / maintain",BLUE);s.text(7,6,"2  Campaign catalog - create / maintain",BLUE);
                s.text(9,6,"3  Versioned throttling policy",BLUE);s.text(11,6,"4  Global customer suppression list",BLUE);
                s.text(13,6,"5  Preview marketing qualification",BLUE);s.text(15,6,"6  Review / finalize / cancel runs",BLUE);
                s.field("choice",18,20,1,"","Selection");footer="ENTER=Select  F3=Main menu";}
            case "CATALOG"->{rows=get(kind);s.text(4,1,kind.toUpperCase(Locale.ROOT)+"  "+rows.size()+" records; offset "+offset,WHITE);
                for(int i=offset;i<Math.min(offset+10,rows.size());i++){var r=rows.get(i);s.text(6+i-offset,1,(i-offset+1)+" "+r.path(kind.equals("suppressions")?"customerId":"id").asText()+" v"+r.path("version").asText()+" "+(r.path("data").path("active").asBoolean()?"ACTIVE":"INACTIVE"),BLUE);}
                s.field("choice",17,20,2,"","Edit row");s.field("id",19,16,60,"",kind.equals("suppressions")?"Customer ID":"New/edit ID");footer="ENTER=Open form  F7/F8=Page  F3=Marketing menu";}
            case "OFFER"->{recordHeader(s);var d=selected.path("data");
                field(s,d,"name",6,"Name",60);field(s,d,"product",7,"Product",20);field(s,d,"active",8,"Active Y/N",1);
                field(s,d,"startsOn",9,"Start date",10);field(s,d,"endsOn",10,"End date",10);
                field(s,d,"minimumAmountUsd",11,"Min amount USD",12);field(s,d,"maximumAmountUsd",12,"Max amount USD",12);
                field(s,d,"illustrativeAprPct",13,"Demo APR %",6);field(s,d,"annualFeeUsd",14,"Annual fee USD",12);field(s,d,"termMonths",15,"Term months",3);
                s.text(18,1,"PL / CC / AL. Card term is 0. Terms are illustrative, not an offer.",WHITE);footer="ENTER=Save new version  F3=Cancel";}
            case "CAMPAIGN"->{recordHeader(s);var d=selected.path("data");field(s,d,"name",6,"Name",60);field(s,d,"active",7,"Active Y/N",1);
                field(s,d,"startsOn",8,"Start date",10);field(s,d,"endsOn",9,"End date",10);field(s,d,"priority",10,"Priority",3);field(s,d,"capacity",11,"Capacity",5);
                field(s,d,"minimumTenureMonths",12,"Min tenure mo",4);field(s,d,"excludeExistingProduct",13,"Exclude held Y/N",1);
                s.field("offerIds",15,16,60,join(d.path("offerIds")),"Offer IDs");s.text(18,1,"Lower priority number runs first. Offer IDs are comma-separated.",WHITE);footer="ENTER=Save new version  F3=Cancel";}
            case "POLICY"->{s.text(5,1,"Rule "+selected.path("ruleVersion").asText()+" / configuration v"+selected.path("version").asText(),CYAN);var d=selected.path("data");
                field(s,d,"cooldownDays",8,"Cooldown days",3);field(s,d,"rollingWindowDays",10,"Window days",3);field(s,d,"maximumReservations",12,"Window maximum",5);field(s,d,"reservationDays",14,"Reserve days",2);
                s.text(17,1,"Counts finalized customer/campaign reservations, not sent messages.",WHITE);s.text(18,1,"Preview is free. Cancellation releases quota. No outreach is sent.",WHITE);footer="ENTER=Save new policy version  F3=Cancel";}
            case "SUPPRESSION"->{s.text(5,1,"Customer: "+selected.path("customerId").asText()+" v"+selected.path("version").asText(),BLUE);
                var d=selected.path("data");field(s,d,"active",8,"Suppress Y/N",1);field(s,d,"reason",10,"Reason",60);field(s,d,"expiresOn",12,"Expiry date",10);
                s.text(16,1,"Blank expiry means no expiry. N releases suppression with history.",WHITE);footer="ENTER=Save suppression revision  F3=Cancel";}
            case "SOURCES"->{rows=api.call("GET","populations",null);s.text(4,1,"Select a Step 1 pre-screen population ("+rows.size()+")",WHITE);
                for(int i=offset;i<Math.min(offset+10,rows.size());i++){var r=rows.get(i);s.text(6+i-offset,1,(i-offset+1)+" "+r.path("id").asText()+" "+r.path("eligibleCustomers").asText()+" customers",BLUE);}
                s.field("choice",18,20,2,"","Population row");footer="ENTER=Select  F7/F8=Page  F3=Marketing menu";}
            case "PREVIEW"->{s.text(5,1,"Population: "+populationId,BLUE);s.text(7,1,"Current eligibility and preferences will be checked again.",WHITE);
                s.field("campaigns",10,16,60,"DEMO-PL,DEMO-CC,DEMO-AL","Campaign IDs");s.text(14,1,"Preview allocates hypothetical slots; it consumes no real quota.",WHITE);
                s.text(16,1,"Request: "+requestId,BLUE);footer="ENTER=Create preview  F3=Cancel";}
            case "RUNS"->{var page=get("runs?offset="+offset+"&limit=10");rows=page.path("items");s.text(4,1,"Marketing runs "+offset+" / "+page.path("total").asText(),WHITE);int i=0;
                for(var r:rows)s.text(6+i,1,(++i)+" "+r.path("id").asText()+" "+r.path("status").asText()+" "+r.path("qualifiedCustomers").asText()+" cust",BLUE);
                s.field("choice",18,20,2,"","Run row");footer="ENTER=Review  F7/F8=Page  F3=Marketing menu";}
            case "RUN"->{selected=get("runs/"+runId);s.text(4,1,"Run: "+runId,BLUE);s.text(5,1,"Status: "+selected.path("status").asText()+"  Policy v"+selected.path("policy").path("version").asText(),YELLOW);
                s.text(7,1,"Customers: "+selected.path("customerCount").asText()+"   Qualified: "+selected.path("qualifiedCustomers").asText(),CYAN);
                s.text(8,1,"Qualified offers: "+selected.path("qualifiedOffers").asText()+"   Reservation groups: "+selected.path("reservationCount").asText(),CYAN);
                int row=10;var reasons=selected.path("reasonCounts").fields();while(reasons.hasNext() && row<17){var e=reasons.next();s.text(row++,1,e.getKey()+": "+e.getValue().asText(),WHITE);}
                s.field("action",18,20,1,"","R/F/C");s.text(19,1,"R=Results  F=Finalize and reserve  C=Cancel and release",YELLOW);footer="ENTER=Action/refresh  F3=Marketing menu";}
            case "RESULTS"->{var page=get("runs/"+runId+"/results?offset="+offset+"&limit=10");rows=page.path("items");s.text(4,1,"Results "+offset+" / "+page.path("total").asText()+" (historical run snapshot)",WHITE);int i=0;
                for(var q:rows){s.text(6+i,1,(++i)+" "+q.path("customerId").asText().substring(0,8)+" "+q.path("offerId").asText()+" "+q.path("outcome").asText(),BLUE);}
                s.field("choice",18,20,2,"","Result row");footer="ENTER=Risk ID / reasons  F7/F8=Page  F3=Marketing menu";}
            case "RESULT"->{s.text(4,1,"Risk ID: "+selected.path("riskId").asText(),YELLOW);s.text(5,1,"Customer: "+selected.path("customerId").asText()+" v"+selected.path("profileVersion").asText(),BLUE);
                s.text(6,1,"Source decision: "+selected.path("sourceDecisionId").asText("none"),BLUE);s.text(7,1,selected.path("campaignId").asText()+" v"+selected.path("campaignVersion").asText()+" / "+selected.path("offerId").asText()+" v"+selected.path("offerVersion").asText(),CYAN);
                s.text(8,1,selected.path("outcome").asText()+" | "+selected.path("ruleVersion").asText()+" / config "+selected.path("policyVersion").asText(),YELLOW);
                s.text(9,1,"Current decision: "+selected.path("currentDecisionId").asText("none"),BLUE);
                var reasons=selected.path("reasons");for(int i=reasonOffset;i<Math.min(reasonOffset+8,reasons.size());i++)s.text(10+i-reasonOffset,1,reasons.get(i).asText(),WHITE);
                s.text(18,1,"Underwriting policy: "+selected.path("underwritingPolicyVersion").asText("none")+"  Suppression v"+selected.path("suppressionVersion").asText("none"),BLUE);
                if(!selected.path("suppressionReason").isNull())s.text(19,1,selected.path("suppressionReason").asText(),WHITE);
                footer="ENTER=Results  F7/F8=More reasons  F3=Marketing menu";}
        }
        return footer;
    }
    /** Loads the selected catalog or qualification resource through the API. */
    private JsonNode get(String path){return api.call("GET","marketing/"+path,null);}
    /** Submits the edited catalog or policy form with its expected revision. */
    private void save(String resource,String key,JsonNode data){api.call("PUT","marketing/"+resource+"/"+selected.path("id").asText(),Map.of("expectedVersion",selected.path("version").asInt(),key,data));view="CATALOG";}
    /** Renders the identifier and revision header for the current catalog record. */
    private void recordHeader(TerminalServer.Screen s){s.text(4,1,selected.path("id").asText()+" / editing v"+selected.path("version").asText(),BLUE);}
    /** Renders a labeled editable catalog field on the 3270 screen. */
    private static void field(TerminalServer.Screen s,JsonNode data,String key,int row,String label,int length){var n=data.path(key);s.field(key,row,16,length,n.isNull()||n.isMissingNode()?"":n.isBoolean()?(n.asBoolean()?"Y":"N"):n.asText(),label);}
    /** Returns the selected displayed row after validating the entered row number. */
    private JsonNode pick(JsonNode list,String choice,boolean localPaging){int i=Integer.parseInt(choice)-1;if(i<0 || i>=10)throw new IllegalArgumentException("Choose a displayed row 1-10");if(localPaging)i+=offset;if(i>=list.size())throw new IllegalArgumentException("Choose a displayed row");return list.get(i);}
    /** Builds the initial form values for a new catalog record. */
    private JsonNode template(String id){
        ObjectNode result=Json.MAPPER.createObjectNode();result.put(kind.equals("suppressions")?"customerId":"id",id);result.put("version",0);ObjectNode d=result.putObject("data");
        d.put("active",true);
        if(kind.equals("suppressions")){api.call("GET","customers/"+id,null);d.put("reason","OPERATOR_HOLD");d.putNull("expiresOn");return result;}
        d.put("name","");d.put("startsOn",LocalDate.now(ZoneOffset.UTC).toString());d.put("endsOn",LocalDate.now(ZoneOffset.UTC).plusYears(1).toString());
        if(kind.equals("offers")){d.put("product","PERSONAL_LOAN");d.put("minimumAmountUsd",1000);d.put("maximumAmountUsd",50000);d.put("illustrativeAprPct",12);d.put("annualFeeUsd",0);d.put("termMonths",36);}
        else{d.put("priority",100);d.put("capacity",1000);d.put("minimumTenureMonths",0);d.put("excludeExistingProduct",false);d.putArray("offerIds");}return result;
    }
    /** Rejects editing when the terminal has no current catalog revision loaded. */
    private void checkEditable(){var d=selected.path("data");if(join(d.path("offerIds")).length()>60)throw new IllegalArgumentException("Offer ID list exceeds terminal width; edit this campaign through the API.");
        if(!java.nio.charset.Charset.forName("IBM037").newEncoder().canEncode(d.toString()))throw new IllegalArgumentException("Record contains characters outside CP037; edit through the API.");}
    /** Returns the trimmed terminal field value, using an empty value when omitted. */
    private static String value(Map<String,String> values,String key){return values.getOrDefault(key,"").trim();}
    /** Converts a terminal response into its boolean form. */
    private static boolean yes(Map<String,String> values,String key){return switch(value(values,key).toUpperCase(Locale.ROOT)){case "Y"->true;case "N"->false;default->throw new IllegalArgumentException(key+": use Y or N");};}
    /** Converts comma-separated terminal input into a list of values. */
    private static List<String> csv(String s){return Arrays.stream(s.split(",")).map(String::trim).filter(x->!x.isEmpty()).toList();}
    /** Joins a collection of values for display in a terminal field. */
    private static String join(JsonNode array){List<String> values=new ArrayList<>();array.forEach(n->values.add(n.asText()));return String.join(",",values);}
    /** Maps a lending product between its API value and terminal display representation. */
    private static String product(String s){return switch(s){case "PL"->"PERSONAL_LOAN";case "CC"->"CREDIT_CARD";case "AL"->"AUTO_LOAN";default->s;};}
}

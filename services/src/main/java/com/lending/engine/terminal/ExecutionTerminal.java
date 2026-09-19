/**
 * Business operations over HTTP: campaign execution console with current customer packages, Business
 * history, channel preferences and file readiness.
 */
package com.lending.engine.terminal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * Business operations over HTTP: campaign execution console with current customer packages, Business
 * history, channel preferences and file readiness.
 */
final class ExecutionTerminal {
    private static final int BLUE=0xf1,CYAN=0xf5,YELLOW=0xf6,WHITE=0xf7;
    private final ApiClient api;private String view="HOME",customer,after="",next,notice="";private JsonNode rows,selected;private int offset;private final Deque<String> previous=new ArrayDeque<>();
    /** Initializes execution terminal with the supplied configuration and dependencies. */
    ExecutionTerminal(ApiClient api){this.api=api;}
    /** Clears the screen state before beginning a new terminal interaction. */
    void reset(){view="HOME";customer=null;after="";previous.clear();notice="";offset=0;}
    /** Sets the customer context for inspecting campaign execution packages. */
    void customer(String id){reset();customer=id;view="LIST";}
    /**
     * Processes terminal keys and submitted fields for the active screen, issuing commands through HTTP
     * APIs.
     */
    boolean handle(int aid,Map<String,String> values){if(aid==0xf3){if(view.equals("HOME"))return true;if(view.equals("DETAIL")){view="LIST";offset=0;}else reset();return false;}if(aid==0xf7||aid==0xf8){if(view.equals("LIST")){if(aid==0xf8&&next!=null){previous.push(after);after=next;}else if(aid==0xf7&&!previous.isEmpty())after=previous.pop();}else if(view.equals("DETAIL"))offset=Math.max(0,Math.min(Math.max(0,selected.path("alternatives").size()-1),offset+(aid==0xf8?4:-4)));return false;}if(aid!=0x7d)return false;String choice=values.getOrDefault("choice","").trim();switch(view){case "HOME"->{view=switch(choice){case "1"->"LIST";case "2"->"TASKS";case "3"->"HEALTH";default->throw new IllegalArgumentException("Choose 1-3");};}case "LIST"->{if(choice.isBlank())break;int i=Integer.parseInt(choice)-1;if(i<0||i>=rows.size())throw new IllegalArgumentException("Choose a displayed package");selected=rows.get(i);view="DETAIL";offset=0;}case "DETAIL"->{if(choice.equalsIgnoreCase("C")||choice.equalsIgnoreCase("D")){var file=api.call("GET","campaign-execution/packages/"+selected.path("id").asText()+"/files?format="+(choice.equalsIgnoreCase("C")?"csv":"dat"),null);notice=file.path("filename").asText()+" v"+file.path("version").asText()+" verified; local file ready.";}}}return false;}
    /** Renders the current workflow state and input fields on the terminal screen. */
    String draw(TerminalServer.Screen s){s.text(3,1,"CAMPAIGN EXECUTION | "+view,YELLOW);String footer="ENTER=Refresh F3=Back";switch(view){
        case "HOME"->{s.text(6,4,"1  Business: final offer packages / outbound files",CYAN);s.text(8,4,"2  Processing / suppression reasons",BLUE);s.text(10,4,"3  Automatic workflow / repository health",BLUE);s.text(14,1,"One package per customer/campaign; all qualified choices retained.",WHITE);s.text(16,1,"Synthetic contacts and local files. No email, SMS or mail is sent.",YELLOW);s.field("choice",20,20,1,"","Selection");}
        case "LIST"->{var page=api.call("GET","campaign-execution/packages?limit=8&after="+after+(customer==null?"":"&customerId="+customer),null);rows=page.path("items");next=page.path("nextAfter").isNull()?null:page.path("nextAfter").asText();int i=0;for(var p:rows)s.text(6+i,1,(++i)+" "+p.path("campaignId").asText()+" "+p.path("status").asText()+" v"+p.path("version").asText()+" "+p.path("leadKind").asText()+" "+p.path("channel").asText(),CYAN);if(rows.isEmpty())s.text(7,1,"No packages yet. Processing and suppression are shown in option 2.",WHITE);s.text(17,1,"Audit list; a package is rechecked before current detail or file access.",WHITE);s.field("choice",20,20,1,"","Package row");footer="ENTER=View/refresh F7/F8=Page F3=Back";}
        case "DETAIL"->{selected=api.call("GET","campaign-execution/packages/"+selected.path("id").asText(),null);s.text(4,1,selected.path("campaignId").asText()+" v"+selected.path("version").asText()+" | Lead: "+selected.path("leadKind").asText()+" | "+selected.path("channel").asText(),CYAN);s.text(5,1,"Customer: "+selected.path("customerId").asText(),BLUE);s.text(6,1,"SYNTHETIC "+selected.path("contact").path("email").asText(),WHITE);s.text(7,1,"Selection: "+selected.path("selectionId").asText("none - highest fit")+" | choices retained",WHITE);s.text(9,1,"Kind            Catalog / amount USD / APR / fit",YELLOW);var a=selected.path("alternatives");for(int i=offset;i<Math.min(offset+4,a.size());i++){var r=a.get(i);s.text(10+i-offset,1,r.path("kind").asText()+" "+r.path("catalogOfferId").asText()+" $"+r.path("terms").path("amountUsd").asText()+" APR "+r.path("terms").path("aprPct").asText()+" FIT "+String.format(Locale.ROOT,"%.3f",r.path("fitScore").asDouble()),CYAN);}s.text(15,1,"Local directory: runtime/outbound/",BLUE);s.text(16,1,selected.path("id").asText(),BLUE);s.text(17,1,"offers.csv + offers.dat (IBM037) + manifest.json",WHITE);s.text(18,1,notice,CYAN);s.field("choice",20,26,1,"","C=CSV D=EBCDIC verify");footer="ENTER=Refresh/verify F7/F8=Choices F3=List";}
        case "TASKS"->{var tasks=api.call("GET","campaign-execution/tasks"+(customer==null?"":"?customerId="+customer),null);int i=0;for(var t:tasks){if(i>=12)break;s.text(5+i++,1,t.path("campaignId").asText()+" "+t.path("state").asText()+" "+t.path("reason").asText(),CYAN);}s.text(18,1,"First 12 of up to 100 recent tasks; full details via API.",WHITE);s.field("refresh",20,20,1,"","Refresh");}
        case "HEALTH"->{var h=api.call("GET","campaign-execution/health",null);int row=6;for(String field:List.of("pipeline","worker","repositoryRecovery","repositoryAfter","packages","exportedDispatches","pendingFiles"))s.text(row++,1,field+": "+h.path(field).asText(),CYAN);s.text(16,1,"Exported dispatches count file generation, never confirmed delivery.",WHITE);s.field("refresh",20,20,1,"","Refresh");}
    }return footer;}
}

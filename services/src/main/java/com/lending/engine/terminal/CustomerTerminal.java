/**
 * HTTP-only customer presentation explains saved consent blockers and provides current offers,
 * exact-term confirmation and customer-correlated marketing file access.
 */
package com.lending.engine.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.lending.engine.infrastructure.Json;
import java.math.RoundingMode;
import java.util.*;

/**
 * HTTP-only customer presentation explains saved consent blockers and provides current offers,
 * exact-term confirmation and customer-correlated marketing file access.
 */
final class CustomerTerminal {
    private static final int BLUE=0xf1,RED=0xf2,CYAN=0xf5,YELLOW=0xf6,WHITE=0xf7;
    private final ApiClient api;
    private String customerId,view="STATUS",after="",next,offerId,scanAfter="",notice="";
    private final Deque<String> previous=new ArrayDeque<>();
    private JsonNode rows,selected,quote,receipt;
    private boolean readable;
    private int fileOffset;private String packageId;private JsonNode download;
    /** Initializes customer terminal with the supplied configuration and dependencies. */
    CustomerTerminal(ApiClient api){this.api=api;}
    /** Opens the customer workflow for the selected customer identifier. */
    void open(String id,boolean status){customerId=id;view=status?"STATUS":"LIST";after="";next=null;scanAfter="";previous.clear();rows=null;selected=null;quote=null;receipt=null;notice="";readable=false;}
    /** Indicates whether the customer waiting screen should poll for updated application progress. */
    boolean autoRefresh(){return view.equals("STATUS");}
    /** Loads the customer campaign packages and file readiness through the API. */
    void files(String id){open(id,false);view="FILES";fileOffset=0;packageId=null;download=null;}
    /** Loads a page of currently qualified original and personalized offers through the API. */
    private String offers(){return "customers/"+customerId+"/offers";}
    /** Changes the displayed offer page while retaining its pagination cursor. */
    private JsonNode page(String cursor){return api.call("GET",offers()+"?limit=6&after="+cursor,null);}
    /** Returns the offer family selected on the current customer screen. */
    private JsonNode current(){return api.call("GET",offers()+"/"+offerId,null);}
    /** Selects the original or personalized terms from the current offer family. */
    private static JsonNode alternative(JsonNode offer,String kind){return offer.path(kind.equals("ORIGINAL")?"preScreen":"marketing");}
    /** Revalidates the displayed offer version and terms before allowing confirmation. */
    private void checkQuote(JsonNode fresh){
        if(quote==null||fresh.path("qualification").path("responseVersion").asLong()!=quote.path("sourceVersion").asLong()||!alternative(fresh,quote.path("kind").asText()).path("terms").equals(quote.path("terms")))
            throw new IllegalArgumentException("Offer changed. F3 returns to the current choices for review.");
    }
    /** Processes customer navigation, quote review, selection confirmation and file-download commands. */
    boolean handle(int aid,Map<String,String> values){
        notice="";
        if(aid==0xf3){if(view.equals("STATUS")||view.equals("LIST")||view.equals("FILES")){open(null,true);return true;}view=view.equals("FILEDETAIL")?"FILES":view.equals("CONFIRM")?"DETAIL":"LIST";quote=null;receipt=null;download=null;return false;}
        if(aid==0xf5){view="STATUS";scanAfter="";return false;}
        if(view.equals("LIST")&&(aid==0xf7||aid==0xf8)){if(aid==0xf8&&next!=null){previous.push(after);after=next;}else if(aid==0xf7&&!previous.isEmpty())after=previous.pop();return false;}
        if(view.equals("FILES")&&(aid==0xf7||aid==0xf8)){fileOffset=Math.max(0,fileOffset+(aid==0xf8?6:-6));return false;}
        if(aid!=0x7d)return false;
        String choice=values.getOrDefault("choice","").trim().toUpperCase(Locale.ROOT);
        switch(view){
            case "FILES"->{if(choice.isBlank())break;if(!readable||rows==null)throw new IllegalArgumentException("Refresh your current packages first");int index=Integer.parseInt(choice)-1;if(index<0||index>=rows.size())throw new IllegalArgumentException("Choose a displayed package");packageId=rows.get(index).path("id").asText();download=null;view="FILEDETAIL";}
            case "FILEDETAIL"->{if(choice.isBlank())break;if(!Set.of("C","D").contains(choice))throw new IllegalArgumentException("C=CSV, D=EBCDIC");download=api.call("GET","customers/"+customerId+"/campaign-packages/"+packageId+"/files?format="+(choice.equals("C")?"csv":"dat"),null);}
            case "STATUS"->{view="LIST";after="";previous.clear();}
            case "LIST"->{if(choice.isEmpty())break;if(!readable||rows==null)throw new IllegalArgumentException("Refresh the current offer list before choosing.");int index;try{index=Integer.parseInt(choice)-1;}catch(NumberFormatException e){throw new IllegalArgumentException("Enter a displayed offer number.");}if(index<0||index>=rows.size())throw new IllegalArgumentException("Choose a displayed offer number.");offerId=rows.get(index).path("id").asText();selected=null;view="DETAIL";}
            case "DETAIL"->{if(choice.isEmpty())break;if(!readable||selected==null)throw new IllegalArgumentException("Refresh current terms before choosing.");String kind=switch(choice){case "O"->"ORIGINAL";case "P"->"PERSONALIZED";default->throw new IllegalArgumentException("Use O for original or P for personalized.");};var option=alternative(selected,kind);if(option.isMissingNode()||option.isNull())throw new IllegalArgumentException("That option is not currently available.");quote=Json.MAPPER.valueToTree(Map.of("requestId",UUID.randomUUID().toString(),"sourceVersion",selected.path("qualification").path("responseVersion").asLong(),"kind",kind,"terms",option.path("terms")));view="CONFIRM";}
            case "CONFIRM"->{checkQuote(current());receipt=api.call("POST","offer-creation/customers/"+customerId+"/offers/"+offerId+"/selections",quote);view="SAVED";}
            case "SAVED"->{view="LIST";after="";previous.clear();quote=null;receipt=null;}
        }
        return false;
    }
    /**
     * Completes authoritative eligibility reads before rendering current offers and saved consent blockers.
     * Selection receipts show historical confirmation without asserting continuing eligibility.
     */
    String draw(TerminalServer.Screen s){
        readable=false;String footer="ENTER=Refresh  F3=Customer menu";

        try{
            if(view.equals("FILES")){var packages=api.call("GET","customers/"+customerId+"/campaign-packages",null);var all=packages.path("items");var page=Json.MAPPER.createArrayNode();fileOffset=Math.min(fileOffset,Math.max(0,((all.size()-1)/6)*6));for(int i=fileOffset;i<Math.min(fileOffset+6,all.size());i++)page.add(all.get(i));rows=page;readable=true;s.center(3,"My Marketing Files / Delivery Status",WHITE);int i=0;for(var p:rows)s.text(6+i,2,(++i)+" "+p.path("campaignId").asText()+" v"+p.path("version").asText()+" "+p.path("channel").asText()+" "+p.path("leadKind").asText(),BLUE);if(rows.isEmpty())s.text(6,2,"No currently available outbound packages.",WHITE);s.text(13,2,"Processing / delivery restrictions",YELLOW);int row=14;for(var t:packages.path("tasks")){if(row>17)break;s.text(row++,2,t.path("state").asText()+" "+t.path("reason").asText(),WHITE);}s.text(18,2,"Qualified offers remain available independently of file restrictions.",CYAN);s.field("choice",20,24,1,"","Select package");return "ENTER=View/refresh F7/F8=Page F3=Customer menu";}
            if(view.equals("FILEDETAIL")){var packages=api.call("GET","customers/"+customerId+"/campaign-packages",null);JsonNode pack=null;for(var p:packages.path("items"))if(p.path("id").asText().equals(packageId))pack=p;if(pack==null)throw new IllegalArgumentException("Package is no longer current or files are blocked.");if(download!=null&&download.path("version").asLong()!=pack.path("version").asLong())download=null;s.center(3,"My Local Marketing Files",WHITE);s.text(5,2,"Campaign: "+pack.path("campaignId").asText(),BLUE);s.text(7,2,"Channel: "+pack.path("channel").asText()+" | Package v"+pack.path("version").asText(),CYAN);s.text(9,2,"Folder in Lending-Intelligence-Engine: runtime/outbound/",WHITE);s.text(10,2,packageId,BLUE);s.text(12,2,"offers.csv (UTF-8), offers.dat (IBM037), manifest.json",WHITE);s.text(14,2,"Synthetic contact data; files are local. No message has been sent.",YELLOW);if(download!=null){s.text(16,2,"Verified "+download.path("filename").asText()+" / "+download.path("encoding").asText(),CYAN);s.text(17,2,"SHA256: "+download.path("sha256").asText(),BLUE);}s.field("choice",20,27,1,"","C=CSV D=EBCDIC verify");return "ENTER=Verify current file / Refresh F3=My files";}
            if(view.equals("STATUS")){
                var customer=api.call("GET","customers/"+customerId,null);var page=page(scanAfter);
                if(!page.path("items").isEmpty()){after=scanAfter;view="LIST";previous.clear();}
                else{
                    scanAfter=page.path("nextAfter").asText("");
                    s.center(3,"Application Progress",WHITE);s.text(5,2,"Customer: "+customerId,BLUE);
                    String state=customer.path("pipeline").path("state").asText("NOT_STARTED");
                    s.text(8,4,switch(state){case "NO_ELIGIBLE_OFFERS"->"No qualified offers are available at this time.";case "ATTENTION_REQUIRED","STOPPED"->"Processing needs attention. Please review your profile.";case "COMPLETED"->"Processing complete. Checking current offers...";case "RETRYING"->"A service is unavailable. Processing will retry automatically.";default->"Your application is being processed. Please wait...";},CYAN);
                    s.text(10,4,"Progress: "+state.replace('_',' '),BLUE);
                    if(state.equals("NO_ELIGIBLE_OFFERS"))s.text(11,4,consentBlocker(customer),YELLOW);
                    s.text(13,4,"Underwriting > Marketing > Bureau > Offers > Delivery",WHITE);
                    s.text(15,4,"This screen refreshes automatically every two seconds.",BLUE);
                    s.text(17,4,"Only currently qualified offers will be shown.",WHITE);
                    s.text(18,4,"Delivery restrictions do not hide otherwise eligible offers.",WHITE);
                    s.field("refresh",20,24,1,"","ENTER to check offers");return "ENTER=My offers  F3=Customer menu";
                }
            }
            if(view.equals("LIST")){
                var page=page(after);rows=page.path("items");next=page.path("nextAfter").asText(null);readable=true;
                s.center(3,"My Qualified Offers",WHITE);s.text(4,2,"Customer: "+customerId,BLUE);
                int i=0;for(var offer:rows){int row=6+i*2;s.text(row,4,String.format("%02d. %-15s %s",++i,product(offer.path("product").asText()),offer.path("qualification").path("catalogOfferId").asText()),BLUE);s.text(row+1,8,(offer.path("preScreen").isObject()?"Original":"")+(offer.path("marketing").isObject()?" / Personalized":"")+"  | Selected: "+offer.path("selection").path("kind").asText("none"),CYAN);}
                if(rows.isEmpty()){s.text(8,4,"No currently qualified offers on this page.",WHITE);s.text(10,4,next!=null?"More records exist. Use F8 to continue.":"Processing may still be running. F5 shows progress.",BLUE);}
                s.text(18,2,"Eligibility checked now. Copies may take a few seconds to update.",WHITE);
                s.field("choice",20,24,2,"","Select offer number");return "ENTER=View/Refresh  F5=Progress  F7/F8=Page  F3=Customer menu";
            }
            if(view.equals("DETAIL")){
                selected=current();readable=true;s.center(3,"Compare Your Offers",WHITE);
                s.text(4,2,product(selected.path("product").asText())+" | "+selected.path("qualification").path("catalogOfferId").asText()+" | USD",BLUE);
                s.text(6,30,"ORIGINAL",BLUE);s.text(6,52,"PERSONALIZED",CYAN);
                var original=selected.path("preScreen");var personal=selected.path("marketing");
                comparison(s,7,"Amount / credit limit",original,personal,"terms","amountUsd",true);
                comparison(s,8,"APR (%)",original,personal,"terms","aprPct",false);
                comparison(s,9,"Annual fee",original,personal,"terms","annualFeeUsd",true);
                s.text(10,2,"Term",WHITE);s.text(10,30,term(original.path("terms")),BLUE);s.text(10,52,term(personal.path("terms")),CYAN);
                comparison(s,11,"Est. monthly payment",original,personal,"metrics","monthlyPaymentUsd",true);
                comparison(s,12,"Est. borrowing cost",original,personal,"metrics","totalCostUsd",true);
                s.text(14,2,"Selected: "+selected.path("selection").path("kind").asText("none"),CYAN);
                s.text(15,2,"Valid until (UTC): "+original.path("validUntil").asText(personal.path("validUntil").asText("--")),BLUE);
                s.text(16,2,selected.path("product").asText().equals("CREDIT_CARD")?"Card estimates assume a full-limit balance for one year.":"Loan estimates use scheduled amortization, plus annual fees.",WHITE);
                s.text(17,2,personal.isObject()?"Compare cost and payments before choosing.":"A qualified personalized option is not currently available.",WHITE);
                s.text(18,2,"Local demonstration. Selecting an offer does not fund a loan.",YELLOW);
                s.field("choice",20,30,1,"","O=Original P=Personalized");return "ENTER=Review choice / Refresh  F3=Offer list";
            }
            if(view.equals("CONFIRM")){
                var fresh=current();checkQuote(fresh);readable=true;s.center(3,"Confirm Your Choice",WHITE);
                s.text(5,4,product(fresh.path("product").asText())+" / "+quote.path("kind").asText(),CYAN);
                terms(s,quote.path("terms"));s.text(14,4,"ENTER saves the terms shown above as your latest choice.",WHITE);
                s.text(16,4,"Your campaign package will update automatically.",BLUE);
                s.text(17,4,"Delivery still follows consent and channel rules.",BLUE);
                s.text(18,4,"Local demonstration. No loan is funded by this action.",YELLOW);
                s.field("confirm",20,24,1,"","Confirm this choice");return "ENTER=Confirm selection  F3=Back without saving";
            }
            if(view.equals("SAVED")){

                s.center(3,"Selection Saved",WHITE);s.text(5,4,receipt.path("kind").asText()+" choice recorded",CYAN);terms(s,receipt.path("terms"));
                s.text(14,4,"Saved (UTC): "+receipt.path("selectedAt").asText(),BLUE);
                s.text(16,4,"Your offer and campaign copies update automatically.",WHITE);
                s.text(17,4,"Current eligibility is checked again when viewing offers.",WHITE);
                s.text(18,4,"This receipt confirms a demo selection, not loan funding.",YELLOW);
                s.field("continue",20,24,1,"","Return to my offers");return "ENTER=My offers  F3=Offer list";
            }
        }catch(RuntimeException e){
            rows=null;selected=null;next=null;readable=false;
            s.center(3,"Offer Information Unavailable",WHITE);s.text(7,4,"Current eligibility or terms could not be verified.",RED);
            s.text(9,4,"Cached offers are hidden. No new selection is implied.",WHITE);
            s.text(12,4,e.getMessage()==null?"Please retry shortly.":e.getMessage(),BLUE);
            s.field("refresh",20,24,1,"","Retry current view");footer="ENTER=Retry  F3=Back";
        }
        return footer;
    }
    /** Explains a saved consent restriction without inferring eligibility or changing customer preferences. */
    private static String consentBlocker(JsonNode customer){
        var profiles=customer.path("profiles");if(!profiles.isArray()||profiles.isEmpty())return "";
        var data=profiles.get(profiles.size()-1).path("data");
        if(data.path("prescreenOptOut").asBoolean(false))return "Pre-screen opt-out is enabled. Review your saved preferences.";
        var optIn=data.get("marketingOptIn");
        return optIn!=null&&!optIn.asBoolean(false)?"Marketing consent is not enabled. Review your saved preferences.":"";
    }
    /** Maps a lending product between its API value and terminal display representation. */
    private static String product(String value){return switch(value){case "PERSONAL_LOAN"->"Personal loan";case "CREDIT_CARD"->"Credit card";case "AUTO_LOAN"->"Auto loan";default->value;};}
    /** Formats a numeric offer value for terminal display. */
    private static String number(JsonNode n,boolean money){return !n.isNumber()?"--":(money?"$":"")+n.decimalValue().setScale(2,RoundingMode.HALF_UP).toPlainString();}
    /** Formats the repayment term for the customer offer screen. */
    private static String term(JsonNode terms){return !terms.isObject()?"--":terms.path("termMonths").asInt()==0?"Revolving":terms.path("termMonths").asText()+" months";}
    /** Renders the financial and fit comparison between original and personalized offers. */
    private static void comparison(TerminalServer.Screen s,int row,String label,JsonNode a,JsonNode b,String section,String key,boolean money){s.text(row,2,label,WHITE);s.text(row,30,number(a.path(section).path(key),money),BLUE);s.text(row,52,number(b.path(section).path(key),money),CYAN);}
    /** Renders the amount, APR, fee and repayment term for one offer choice. */
    private static void terms(TerminalServer.Screen s,JsonNode t){s.text(8,4,"Amount / limit: "+number(t.path("amountUsd"),true)+" USD",WHITE);s.text(9,4,"APR:            "+number(t.path("aprPct"),false)+"%",WHITE);s.text(10,4,"Annual fee:     "+number(t.path("annualFeeUsd"),true)+" USD",WHITE);s.text(11,4,"Term:           "+term(t),WHITE);}
}

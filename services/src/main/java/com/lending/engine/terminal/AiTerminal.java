package com.lending.engine.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lending.engine.infrastructure.Json;
import java.math.BigDecimal;
import java.util.*;

/** HTTP-only AI terminal journey: bounded search, policy review, publication and researched feedback with locked successors. */
final class AiTerminal {
    private final ApiClient api;
    private String view="HOME",back="HOME",draftId,populationId,validationId,workflowId,notice="",intent="",requestId,publicationId;
    private String flow="NEW",reviewedPreview="";
    private int offset,policyLength,trialCount;private JsonNode rows,workflow,preview,selectedPolicy;private ObjectNode budget;
    private List<String> report=List.of();private String reviewedScope="";
    AiTerminal(ApiClient api){this.api=api;}
    void open(String draft){flow="NEW";view=draft==null?"HOME":"DISCOVERY";draftId=draft;offset=0;notice="";requestId=UUID.randomUUID().toString();}
    private JsonNode get(String path){return api.call("GET","ai/"+path,null);}
    private JsonNode post(String path,Object data){return api.call("POST","ai/"+path,data);}
    private String value(Map<String,String> v,String key){return v.getOrDefault(key,"").trim();}
    private JsonNode pick(String choice){int i=Integer.parseInt(choice)-1; if(rows==null||i<0||i>=rows.size())throw new IllegalArgumentException("Choose a displayed row");return rows.get(i);}
    boolean handle(int aid,Map<String,String> values){
        if(aid==0xf3){if(view.equals("HOME"))return true;view=view.equals("REPORT")?back:view.equals("POLICY_TEXT")?"POLICY":Set.of("TRIALS","EXTEND").contains(view)?"WORKFLOW":"HOME";offset=0;return false;}
        if(aid==0xf7||aid==0xf8){offset=Math.max(0,offset+(aid==0xf8?1:-1)*(view.equals("REPORT")||view.equals("POLICY_TEXT")?13:8));if(view.equals("POLICY_TEXT"))offset=Math.min(offset,Math.max(0,(policyLength-1)/988*13));if(view.equals("TRIALS"))offset=Math.min(offset,Math.max(0,(trialCount-1)/8*8));return false;}
        if(aid!=0x7d)return false;
        String choice=value(values,"choice").replaceFirst("^0","");notice="";
        switch(view){
            case "HOME"->{offset=0;switch(choice){case "1"->{flow="NEW";view="DRAFTS";}case "2"->view="WORKFLOWS";case "3"->view="POLICIES";case "4"->view="PUBLICATIONS";default->throw new IllegalArgumentException("Choose 1-4");}}
            case "DRAFTS"->{draftId=pick(choice).path("id").asText();view="DISCOVERY";offset=0;}
            case "DISCOVERY"->{populationId=pick(choice).path("id").asText();view="VALIDATION";offset=0;}
            case "VALIDATION"->{validationId=pick(choice).path("id").asText();if(validationId.equals(populationId))throw new IllegalArgumentException("Choose a different validation population");view="INTENT";budget=(ObjectNode)get("defaults");requestId=UUID.randomUUID().toString();}
            case "INTENT"->{intent=String.join(" ",value(values,"intent1"),value(values,"intent2"),value(values,"intent3")).trim();if(intent.isBlank())throw new IllegalArgumentException("Describe the fields you want to explore");view="BUDGET";}
            case "BUDGET"->{
                int attempts=Integer.parseInt(value(values,"attempts"));if(attempts<4||attempts>1000)throw new IllegalArgumentException("Use 4-1000 simulation attempts");
                int validation=Math.max(2,attempts/10),refinement=(attempts-validation-1)*3/10,exploration=attempts-validation-refinement-1;
                budget.put("eligibilityFloor",Double.parseDouble(value(values,"eligibilityFloor"))).put("acceptanceFloor",Double.parseDouble(value(values,"acceptanceFloor"))).put("maxEvaluations",attempts).put("exploration",exploration).put("refinement",refinement).put("validation",validation)
                    .put("maxModelCalls",Integer.parseInt(value(values,"calls"))).put("maxTokens",Long.parseLong(value(values,"tokens")))
                    .put("maxCostUsd",new BigDecimal(value(values,"cost"))).put("minimumEligible",Integer.parseInt(value(values,"support")))
                    .put("deadline",java.time.Instant.now().plusSeconds(Long.parseLong(value(values,"minutes"))*60).toString());
                List<String> locks=value(values,"lock").equalsIgnoreCase("Y")?List.of("underwriting"):List.of();
                if(flow.equals("EXPLAIN"))workflow=post("publications/"+publicationId+"/investigations",Map.of("requestId",requestId,"text",intent,"budget",budget,"sourceMode","DEMO"));
                else if(flow.equals("SUCCESSOR"))workflow=post("publications/"+publicationId+"/successors",Map.of("requestId",requestId,"populationId",populationId,"validationPopulationId",validationId,"intent",intent,"lockedStages",locks,"budget",budget,"seed",20260918));
                else workflow=post("workflows",Map.of("requestId",requestId,"draftId",draftId,"populationId",populationId,"validationPopulationId",validationId,"intent",intent,"lockedStages",locks,"budget",budget,"seed",20260918));
                workflowId=workflow.path("id").asText();view="WORKFLOW";offset=0;
            }
            case "WORKFLOWS"->{workflowId=pick(choice).path("id").asText();view="WORKFLOW";offset=0;}
            case "WORKFLOW"->{
                workflow=get("workflows/"+workflowId);var w=workflow.path("workflow");if(choice.isBlank())break;
                switch(choice){
                    case "1"->{reviewedScope=w.path("scopeHash").asText();show(w.path("scope"),"WORKFLOW");}
                    case "2"->show(w,"WORKFLOW");
                    case "3"->{if(!reviewedScope.equals(w.path("scopeHash").asText())||reviewedScope.isBlank())throw new IllegalArgumentException("Inspect the scope first using option 1");view="APPROVE";}
                    case "4"->view="MESSAGE";
                    case "5"->{view="TRIALS";offset=0;}
                    case "6"->view="PREVIEW";
                    case "7"->{workflow=post("workflows/"+workflowId+"/cancel",Map.of());notice="Canceled future work. Any running engine simulation can still finish.";}
                    case "8"->{if(!w.path("state").asText().equals("PAUSED_BUDGET"))throw new IllegalArgumentException("Only a budget-paused workflow can be extended");budget=(ObjectNode)w.path("budget").deepCopy();view="EXTEND";}
                    default->throw new IllegalArgumentException("Choose 1-8");
                }
            }
            case "TRIALS"->{if(!choice.isBlank())show(pick(choice),"TRIALS");}
            case "EXTEND"->{budget.put("maxModelCalls",Integer.parseInt(value(values,"calls"))).put("maxTokens",Long.parseLong(value(values,"tokens"))).put("maxCostUsd",new BigDecimal(value(values,"cost"))).put("deadline",java.time.Instant.now().plusSeconds(Long.parseLong(value(values,"minutes"))*60).toString());workflow=post("workflows/"+workflowId+"/resume",Map.of("expectedVersion",workflow.path("version").asInt(),"budget",budget));view="WORKFLOW";}
            case "APPROVE"->{if(!value(values,"confirm").equalsIgnoreCase("A"))throw new IllegalArgumentException("Type A to approve these ranges and budget");workflow=post("workflows/"+workflowId+"/scope-confirmations",Map.of("expectedVersion",workflow.path("version").asInt(),"scopeHash",reviewedScope));view="WORKFLOW";}
            case "MESSAGE"->{String text=String.join(" ",value(values,"intent1"),value(values,"intent2"),value(values,"intent3")).trim();workflow=post("workflows/"+workflowId+"/messages",Map.of("expectedVersion",workflow.path("version").asInt(),"text",text));view="WORKFLOW";}
            case "PREVIEW"->{
                workflow=get("workflows/"+workflowId);if(!Set.of("A","E").contains(value(values,"winner").toUpperCase(Locale.ROOT)))throw new IllegalArgumentException("Choose A or E");String key=value(values,"winner").equalsIgnoreCase("A")?"acceptance":"eligibility";var winner=workflow.path("workflow").path("winners").path(key);
                if(winner.isNull()||winner.isMissingNode())throw new IllegalArgumentException("That winner is unavailable; inspect results");
                preview=post("workflows/"+workflowId+"/publication-previews",Map.of("candidateId",winner.path("candidateId").asText(),"note",value(values,"note")));view="PUBLISH";
            }
            case "PUBLISH"->{if(choice.equals("1")){reviewedPreview=preview.path("previewHash").asText();show(preview,"PUBLISH");}else if(value(values,"confirm").equalsIgnoreCase("P")){if(!reviewedPreview.equals(preview.path("previewHash").asText()))throw new IllegalArgumentException("Inspect the complete publication preview using option 1 first");var receipt=post("publication-previews/"+preview.path("previewId").asText()+"/confirm",Map.of("previewHash",preview.path("previewHash").asText(),"confirm",true));show(receipt,"HOME");}else throw new IllegalArgumentException("Inspect the preview or type P to publish");}
            case "POLICIES"->{if(choice.equalsIgnoreCase("R")){post("policies/refresh",Map.of());notice="Policy folder refreshed";}else{selectedPolicy=pick(choice);view="POLICY";}}
            case "POLICY"->{if(choice.equals("1")){view="POLICY_TEXT";offset=0;}else if(choice.equals("2"))view="POLICY_APPROVE";else throw new IllegalArgumentException("Choose text review or approval");}
            case "POLICY_APPROVE"->{if(!value(values,"confirm").equalsIgnoreCase("A"))throw new IllegalArgumentException("Type A only after reviewing the extracted policy");selectedPolicy=post("policies/"+selectedPolicy.path("id").asText()+"/approve",Map.of("hash",selectedPolicy.path("hash").asText(),"product",value(values,"product"),"geography","US","expiresOn",value(values,"expires")));view="POLICY";}
            case "PUBLICATIONS"->{publicationId=pick(choice).path("id").asText();view="FEEDBACK";}
            case "FEEDBACK"->{switch(choice){
                case "1"->show(get("publications/"+publicationId+"/performance"),"FEEDBACK");
                case "2"->{flow="EXPLAIN";view="INTENT";budget=(ObjectNode)get("defaults");requestId=UUID.randomUUID().toString();}
                case "3"->{flow="SUCCESSOR";view="DISCOVERY";offset=0;requestId=UUID.randomUUID().toString();}
                case "4"->{view="WORKFLOWS";offset=0;}
                default->throw new IllegalArgumentException("Choose performance, explanation, successor or saved workflow");
            }}
            default->{}
        }
        return false;
    }
    private void show(JsonNode data,String parent){var lines=new ArrayList<String>();flatten("",data,lines);report=List.copyOf(lines);back=parent;view="REPORT";offset=0;}
    private static void flatten(String path,JsonNode data,List<String> lines){
        if(data.isObject())data.fields().forEachRemaining(e->flatten(path.isBlank()?e.getKey():path+" / "+e.getKey(),e.getValue(),lines));
        else if(data.isArray()){int i=0;for(var n:data)flatten(path+" ["+(i++)+"]",n,lines);}
        else{String text=path+": "+(data.isNull()?"unavailable":data.asText());for(int i=0;i<text.length();i+=76)lines.add(text.substring(i,Math.min(text.length(),i+76)));}
    }
    String draw(TerminalServer.Screen s){
        final int blue=0xf1,cyan=0xf5,yellow=0xf6,white=0xf7;s.center(3,"AI-assisted Business Simulation",white);String footer="ENTER=Continue F3=Back";
        switch(view){
            case "HOME"->{String[] choices={"Explore an existing draft","Resume a workflow / inspect results","Review local policy documents","Published offer performance"};for(int i=0;i<choices.length;i++)s.text(7+i*2,4,(i+1)+"  "+choices[i],blue);s.text(17,2,"Six Java agents use GPT-6 Astra. Model requests can incur configured costs.",yellow);s.menuField();}
            case "DRAFTS","DISCOVERY","VALIDATION","WORKFLOWS","POLICIES","PUBLICATIONS"->{
                JsonNode all=switch(view){case "DRAFTS"->api.call("GET","business/drafts",null);case "DISCOVERY","VALIDATION"->api.call("GET","business/populations",null);case "WORKFLOWS"->get("workflows?limit=100");case "POLICIES"->get("policies").path("documents");default->get("publications");};
                var page=Json.MAPPER.createArrayNode();for(int i=offset;i<Math.min(offset+8,all.size());i++)page.add(all.get(i));rows=page;
                s.text(5,2,view+" - select a row"+(view.equals("VALIDATION")?" with a different generation seed":""),yellow);
                int i=0;for(var row:rows){String text=switch(view){case "DRAFTS"->row.path("name").asText()+" v"+row.path("version").asText();case "DISCOVERY","VALIDATION"->row.path("count").asText()+" customers / seed "+row.path("seed").asText();case "WORKFLOWS"->row.path("data").path("state").asText()+" "+row.path("data").path("intent").asText();case "POLICIES"->row.path("name").asText()+" / "+row.path("extractionStatus").asText()+" / approved="+row.path("approved").asText();default->row.path("id").asText();};s.text(7+i,2,(++i)+"  "+text,blue);}
                if(rows.isEmpty())s.text(8,2,"No records. Create drafts/populations in the manual Business options.",white);
                if(view.equals("POLICIES"))s.text(17,2,"Add documents in policy-documents, then enter R to refresh.",cyan);
                s.field("choice",20,23,2,"","Select row");footer="ENTER=Select F7/F8=Page F3=Back";
            }
            case "INTENT","MESSAGE"->{s.text(6,2,flow.equals("EXPLAIN")?"Ask why performance differs, or which engine stages deserve investigation.":"Describe the change. Specify underwriting, marketing or bureau for scores.",white);for(int i=1;i<=3;i++)s.field("intent"+i,8+i*2,3,73,"","");s.text(17,2,"Unmentioned fields stay fixed. Explain any constraints or desired ranges.",cyan);}
            case "BUDGET"->{s.field("attempts",6,35,4,"200","Simulation attempt limit");s.field("minutes",7,35,4,"30","Wall-clock minutes");s.field("calls",8,35,4,"60","Model call limit");s.field("tokens",9,35,8,"500000","Token reservation limit");s.field("cost",10,35,8,budget.path("maxCostUsd").asText(),"Maximum reserved model cost USD");s.field("support",11,35,5,"30","Minimum eligible support");s.field("lock",12,35,1,flow.equals("SUCCESSOR")?"Y":"N","Lock all underwriting? Y/N");s.field("eligibilityFloor",13,35,6,"0","Acceptance winner: eligibility floor %");s.field("acceptanceFloor",14,35,6,"0","Eligibility winner: acceptance floor %");s.text(16,2,"Coarse exploration, refinement and separate final validation are budgeted.",white);s.text(18,2,"ENTER authorizes planning within these limits; ranges need later review.",yellow);}
            case "WORKFLOW"->{workflow=get("workflows/"+workflowId);var w=workflow.path("workflow");s.text(5,2,"State: "+w.path("state").asText()+" / revision "+w.path("revision").asText(),cyan);s.text(6,2,"Trials used: "+workflow.path("usage").path("evaluations").asText()+" / "+w.path("budget").path("maxEvaluations").asText(),white);
                if(w.has("error"))s.text(7,2,w.path("error").path("message").asText(),yellow);else if(w.has("clarification"))s.text(7,2,w.path("clarification").asText(),yellow);
                String[] options={"Inspect exact scope / ranges / evidence IDs","Inspect analysis, limitations and workflow","Approve reviewed scope and start simulations","Answer clarification / revise intent","All simulation results and trial status","Preview a tested winner for publication","Cancel further work","Extend a paused budget / deadline"};for(int i=0;i<options.length;i++)s.text(9+i,2,(i+1)+" "+options[i],blue);
                s.text(17,2,"Acceptance winner: "+w.path("winners").path("acceptance").path("acceptancePct").asText("pending")+"% among eligible",white);s.text(18,2,"Eligibility winner: "+w.path("winners").path("eligibility").path("eligibilityPct").asText("pending")+"% of assessed",white);s.field("choice",20,23,1,"","Option / blank refresh");}
            case "TRIALS"->{var page=get("workflows/"+workflowId+"/evaluations?offset="+offset+"&limit=8");rows=page.path("items");trialCount=page.path("total").asInt();int i=0;for(var row:rows)s.text(7+i,2,(++i)+" "+row.path("phase").asText()+" "+row.path("status").asText()+" A="+row.path("score").path("acceptancePct").asText()+" E="+row.path("score").path("eligibilityPct").asText(),blue);s.text(17,2,"Trials "+(offset+1)+"-"+(offset+rows.size())+" of "+page.path("total").asInt(),white);s.field("choice",20,23,1,"","Inspect trial");footer="ENTER=Details F7/F8=Page F3=Back";}
            case "POLICY_TEXT"->{var page=get("policies/"+selectedPolicy.path("id").asText()+"/text?offset="+offset*76);policyLength=page.path("totalCharacters").asInt();String text=page.path("text").asText().replace('\n',' ').replace('\r',' ').replace('\t',' ');for(int i=0;i<Math.min(13,(text.length()+75)/76);i++)s.text(5+i,1,text.substring(i*76,Math.min((i+1)*76,text.length())),white);s.text(20,2,"Source character offset "+offset*76+" / "+page.path("totalCharacters").asInt(),cyan);footer="F7/F8=Text page F3=Back";}
            case "EXTEND"->{s.text(6,2,"Increase limits and deadline. Search phases and ranking stay fixed.",yellow);s.field("minutes",9,35,4,"60","Minutes from now");s.field("calls",11,35,4,budget.path("maxModelCalls").asText(),"Model call limit");s.field("tokens",13,35,8,budget.path("maxTokens").asText(),"Token limit");s.field("cost",15,35,8,budget.path("maxCostUsd").asText(),"Maximum reserved cost USD");s.text(18,2,"ENTER approves the extension and resumes the saved plan.",yellow);}
            case "APPROVE"->{s.text(6,2,"Approve the exact scope, evidence-backed ranges, locks and budget.",yellow);s.text(9,2,"Scope hash: "+reviewedScope,blue);s.field("confirm",17,32,1,"","Type A to start");}
            case "PREVIEW"->{s.text(6,2,"Choose one completed final-validation winner and add your review note.",white);s.field("winner",10,32,1,"A","Acceptance A / Eligibility E");s.field("note",13,16,60,"","Review note");}
            case "PUBLISH"->{s.text(6,2,"Publication creates a new offer and campaign in the active catalog.",yellow);s.text(8,2,"1 Inspect complete terms, rules, exact changes and report",blue);s.text(10,2,"Run "+preview.path("runId").asText(),white);s.text(12,2,"Note: "+preview.path("note").asText(),white);s.field("choice",17,25,1,"","Inspect option");s.field("confirm",19,32,1,"","Type P to publish");}
            case "FEEDBACK"->{s.text(6,2,"Published catalog offer "+publicationId,cyan);s.text(8,2,"1 Current selections / eligible and the saved estimate",blue);s.text(10,2,"2 Ask why: research, eight-stage diagnostics and reflection",blue);s.text(12,2,"3 Explore edits using a fresh baseline and validation population",blue);s.text(14,2,"4 Inspect saved investigations and successor experiments",blue);s.text(17,2,"Native customer data is DEMO. Utility estimates are not real forecasts.",yellow);s.field("choice",20,23,1,"","Select");}
            case "POLICY"->{s.text(6,2,selectedPolicy.path("name").asText(),cyan);s.text(8,2,"Extraction: "+selectedPolicy.path("extractionStatus").asText()+" / chars "+selectedPolicy.path("characterCount").asText(),white);s.text(10,2,"Approved: "+selectedPolicy.path("approved").asText(),white);s.text(13,2,"1 Review extracted text and metadata",blue);s.text(15,2,"2 Approve this exact document version",blue);s.field("choice",20,23,1,"","Select");}
            case "POLICY_APPROVE"->{s.text(6,2,"Approve only after checking the extracted text and applicability.",yellow);s.field("product",10,30,20,"ALL","Product or ALL");s.field("expires",12,30,10,java.time.LocalDate.now().plusYears(1).toString(),"Approval expires YYYY-MM-DD");s.field("confirm",17,30,1,"","Type A to approve");}
            case "REPORT"->{offset=Math.min(offset,Math.max(0,report.size()-1));for(int i=offset;i<Math.min(offset+13,report.size());i++)s.text(5+i-offset,1,report.get(i),white);s.text(20,2,"Lines "+(offset+1)+"-"+Math.min(offset+13,report.size())+" / "+report.size(),cyan);footer="F7/F8=Page F3=Back";}
            default->{}
        }
        if(!notice.isBlank())s.text(19,1,notice,cyan);return footer;
    }
}

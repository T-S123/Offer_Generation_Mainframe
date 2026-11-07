package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Customer-clicked workflow passes specialist findings into design and guidance; owner APIs qualify/issue, and only the customer can accept. */
public final class PersonalizationWorkflowService extends WorkflowRuntime {
    private final EngineGateway engine;
    public PersonalizationWorkflowService(Store store,Map<Role,A2AClient> agents,EngineGateway engine){super(store,agents,Network.PERSONALIZATION);this.engine=engine;}
    protected String initialState(){return "PERSONALIZATION_PENDING";}
    protected Set<String> activeStates(){return Set.of("PERSONALIZATION_PENDING");}
    protected JsonNode executionContext(JsonNode w){return obj("network",network,"customerId",w.path("customerId"),"profileVersion",w.path("profileVersion"),"authority","Propose within approved policy. Owner APIs qualify and issue; customer acceptance is never an agent action.","budget",w.path("budget"));}
    private static BigDecimal maxCost(){var value=new BigDecimal(System.getenv().getOrDefault("AI_CUSTOMER_MAX_COST_USD","15"));require(value.signum()>0&&value.compareTo(new BigDecimal("15"))<=0,"CUSTOMER_BUDGET","Customer cost ceiling must be greater than zero and at most 15 USD");return value;}
    public static JsonNode limits(){return obj("trigger","CUSTOMER_BUTTON","maxCandidates",3,"maxModelCalls",10,"maxTokens",300000,"maxCostUsd",maxCost(),"timeoutSeconds",180);}
    private static String principal(String customer){return "customer:"+customer;}
    private static String identity(String customer,int version){return hash(List.of("customer-personalization",customer,version));}
    private int profile(String customer){var c=engine.get("customers/"+customer);int version=0;for(var p:c.path("profiles"))version=Math.max(version,p.path("version").asInt());require(version>0,"PROFILE_REQUIRED","Saved customer profile required");return version;}
    /** A single idempotent run per saved profile prevents repeated clicks from authorizing duplicate spend. */
    public synchronized JsonNode create(JsonNode body,String customer){
        fields(body,"profileVersion");require(customer.matches("[A-Za-z0-9_.:-]{1,120}")&&body.path("profileVersion").isIntegralNumber(),"CUSTOMER_SCOPE","Customer and profile version required");int version=body.path("profileVersion").asInt();require(profile(customer)==version,"STALE_PROFILE","Profile changed; reload your information");
        String id=identity(customer,version);var prior=store.find("WORKFLOW",id);if(prior.isPresent())return customerView(customer,id);
        for(var old:list(principal(customer),null,100))if(activeStates().contains(old.data().path("state").asText()))cancel(old.id(),principal(customer));
        var budget=new Store.Budget(4,10,300000,maxCost(),Instant.now().plusSeconds(180).toString(),1,0,2,1,0,0);
        var data=obj("id",id,"analyst",principal(customer),"kind","PERSONALIZATION","network",network,"customerId",customer,"profileVersion",version,"intent","Find an individually qualified offer with better fit and affordable terms.","revision",1,"state",initialState(),"createdAt",Instant.now().toString(),"budget",budget,"steps",obj(),"evaluations",List.of(),"messages",List.of(),"answers",List.of());
        store.budget(id,budget);store.create("WORKFLOW",id,data);return customerView(customer,id);
    }
    public JsonNode latest(String customer,int version){var saved=store.find("WORKFLOW",identity(customer,version));return saved.isEmpty()?obj("state","NOT_STARTED","limits",limits()):customerView(customer,saved.get().id());}
    /** Returns only this customer's workflow and marks stale versions unusable before exposing navigation. */
    public JsonNode customerView(String customer,String id){var saved=get(id,principal(customer));var w=saved.data();if(profile(customer)!=w.path("profileVersion").asInt()){if(!w.path("state").asText().equals("CANCELED"))cancel(id,principal(customer));return obj("id",id,"state","STALE_PROFILE","profileVersion",w.path("profileVersion"),"actions",List.of(),"message","Your profile changed. Find an offer using the latest information.");}
        if(w.path("state").asText().equals("COMPLETED"))try{var currentOffer=engine.get("offer-creation/customers/"+customer+"/offers/"+w.at("/issued/id").asText());
            if(!currentOffer.at("/personalized/variantQualificationId").equals(w.at("/issued/personalized/variantQualificationId"))||!hash(currentOffer.at("/personalized/terms")).equals(hash(w.at("/issued/personalized/terms"))))return obj("id",id,"state","OFFER_CHANGED","actions",List.of(),"summary","The offered terms changed. Review your currently valid offers.");
        }catch(Fault unavailable){if(unavailable.status!=404&&unavailable.status!=409)throw unavailable;return obj("id",id,"state","OFFER_CHANGED","actions",List.of(),"summary","This personalized offer has expired or its qualification changed. Review your currently valid offers.");}
        return obj("id",id,"version",saved.version(),"state",w.path("state"),"profileVersion",w.path("profileVersion"),"summary",w.hasNonNull("summary")?w.path("summary"):w.at("/outcome/detail"),"offer",w.path("issued"),"qualification",w.path("qualified"),"actions",w.path("actions"),"pendingQuestion",w.path("pendingQuestion"),"error",w.path("error"),"limits",limits(),"usage",store.budget(id));}
    public JsonNode cancelCustomer(String customer,String id){get(id,principal(customer));cancel(id,principal(customer));return customerView(customer,id);}
    public JsonNode reply(String customer,String id,JsonNode body){get(id,principal(customer));message(id,principal(customer),body);return customerView(customer,id);}
    private void fresh(String id){var w=current(id);if(profile(w.path("customerId").asText())!=w.path("profileVersion").asInt()){cancel(id,w.path("analyst").asText());throw new Fault(409,"STALE_PROFILE","Customer changed; stale personalization canceled");}require(Instant.now().isBefore(Instant.parse(w.at("/budget/deadline").asText())),"DEADLINE","Personalization time limit reached; existing valid offers remain available");}
    protected void executeState(String id,String state){if(!state.equals(initialState()))return;fresh(id);var w=current(id);String customer=w.path("customerId").asText(),base="offer-creation/customers/"+customer+"/";
        if(!w.has("context")){var context=engine.get(base+"personalization-context?profileVersion="+w.path("profileVersion").asInt());if(context.path("offers").isEmpty()){mutate(id,n->n.put("summary","Waiting for current qualification; previous offers are context only."));return;}mutate(id,n->n.set("context",context));}
        w=current(id);var context=obj("customer",w.path("context"),"profileVersion",w.path("profileVersion"));
        for(var stage:List.of(Role.ORCHESTRATOR,Role.CONTEXT)){fresh(id);var result=autonomousAgent(id,stage,"customer-"+stage.name().toLowerCase(Locale.ROOT),context);if(stopIfUnresolved(id,result))return;context.set(stage==Role.ORCHESTRATOR?"personalizationPlan":"contextAnalysis",tree(result.decision()));}
        if(!current(id).has("qualified"))for(int attempt=0;attempt<3;attempt++){
            fresh(id);var input=context.deepCopy();input.set("previousCandidates",current(id).path("attempts"));var designed=autonomousAgent(id,Role.DESIGNER,"customer-design-"+attempt,input);if(stopIfUnresolved(id,designed))return;
            var proposal=designed.decision().customerProposal();require(proposal!=null,"CANDIDATE_REQUIRED","Designer must return exact customer terms");JsonNode selected=null;for(var item:current(id).at("/context/offers"))if(item.at("/response/id").asText().equals(proposal.responseId()))selected=item;
            require(selected!=null,"CUSTOMER_SCOPE","Candidate must use a currently qualified offer for this customer");
            String key="CP-"+hash(List.of(id,attempt,proposal)).substring(0,60);var request=obj("requestId",key,"responseId",proposal.responseId(),"responseVersion",selected.at("/response/version"),"policyVersion",selected.path("policyVersion"),"terms",proposal.terms(),"profileVersion",w.path("profileVersion"),"customerPolicyVersion",current(id).at("/context/personalizationPolicy/version"),"ruleChanges",proposal.ruleChanges());
            fresh(id);JsonNode result;try{result=engine.post(base+"personalization-candidates",request);}catch(Fault invalid){if(invalid.status!=422)throw invalid;result=obj("approved",false,"rejection",invalid.getMessage());}
            final var evaluation=result;mutate(id,n->{if(!n.has("attempts"))n.set("attempts",MAPPER.createArrayNode());((com.fasterxml.jackson.databind.node.ArrayNode)n.path("attempts")).add(evaluation);});
            if(result.at("/approval/approved").asBoolean()&&result.path("improvesFit").asBoolean()){mutate(id,n->n.set("qualified",evaluation));break;}
        }
        w=current(id);if(!w.has("qualified")){mutate(id,n->n.put("state","NO_QUALIFIED_IMPROVEMENT").put("summary","No better qualified candidate was found within the approved limits. Your currently valid offers remain available."));return;}
        var check=autonomousAgent(id,Role.ELIGIBILITY,"customer-qualification",obj("qualification",w.path("qualified"),"profileVersion",w.path("profileVersion")));if(stopIfUnresolved(id,check))return;
        fresh(id);if(!current(id).has("issued")){var issued=engine.post(base+"personalization-variants",obj("approvalId",w.at("/qualified/approval/id"),"profileVersion",w.path("profileVersion")));mutate(id,n->n.set("issued",issued));}
        var guide=autonomousAgent(id,Role.GUIDE,"customer-guidance",obj("issued",current(id).path("issued"),"metrics",current(id).path("qualified"),"contextAnalysis",context.path("contextAnalysis"),"steps",List.of("OPEN_OFFER","REVIEW_TERMS","CUSTOMER_CONFIRM")));if(stopIfUnresolved(id,guide))return;
        fresh(id);String offer=current(id).at("/issued/id").asText();mutate(id,n->{n.put("state","COMPLETED").put("summary",guide.decision().summary());n.set("actions",tree(List.of(obj("type","OPEN_OFFER","offerId",offer),obj("type","REVIEW_TERMS","offerId",offer),obj("type","CUSTOMER_CONFIRM","offerId",offer))));});
    }
    /** Read tools never evaluate, issue or accept; commands are admitted by the workflow at explicit stages. */
    public JsonNode tool(Role role,Request request,String name,JsonNode args){fields(args);authorizeTool(role,request);var w=current(request.workflowId());return switch(name){
        case "customer.context" -> {require(role==Role.CONTEXT,"TOOL_DENIED","Customer context role required");var c=w.path("context");yield obj("customerId",c.path("customerId"),"profileVersion",c.path("profileVersion"),"currentFeatures",c.path("currentFeatures"),"previousProfile",c.path("previousProfile"),"previousOffers",c.path("previousOffers"));}
        case "personalization.limits" -> {require(role==Role.DESIGNER,"TOOL_DENIED","Designer role required");yield w.at("/context/personalizationPolicy");}
        case "qualification.result" -> {require(role==Role.ELIGIBILITY,"TOOL_DENIED","Qualification role required");yield w.path("qualified");}
        case "journey.steps" -> {require(role==Role.GUIDE,"TOOL_DENIED","Guide role required");yield obj("offer",w.path("issued"),"steps",List.of("OPEN_OFFER","REVIEW_TERMS","CUSTOMER_CONFIRM"));}
        default -> throw new Fault(422,"TOOL_DENIED","Unknown customer tool");};}
}

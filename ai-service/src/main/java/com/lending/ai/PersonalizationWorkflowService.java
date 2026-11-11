package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Session-bound conversational design reviews customer feedback between candidates; owner APIs qualify temporary quotes and only customers select them. */
public final class PersonalizationWorkflowService extends WorkflowRuntime {
    private final EngineGateway engine;
    public PersonalizationWorkflowService(Store store,Map<Role,A2AClient> agents,EngineGateway engine){super(store,agents,Network.PERSONALIZATION);this.engine=engine;}
    protected String initialState(){return "PERSONALIZATION_PENDING";}
    protected Set<String> activeStates(){return Set.of("PERSONALIZATION_PENDING");}
    protected JsonNode executionContext(JsonNode w){return obj("network",network,"customerId",w.path("customerId"),"profileVersion",w.path("profileVersion"),"authority","Propose within approved policy. Owner APIs qualify and issue; customer acceptance is never an agent action.","budget",w.path("budget"),"conversation",PersonalizationConversation.context(w));}
    private static BigDecimal maxCost(){var value=new BigDecimal(System.getenv().getOrDefault("AI_CUSTOMER_MAX_COST_USD","15"));require(value.signum()>0&&value.compareTo(new BigDecimal("15"))<=0,"CUSTOMER_BUDGET","Customer cost ceiling must be greater than zero and at most 15 USD");return value;}
    public static JsonNode limits(){return obj("trigger","PROFILE_UPDATE_OR_CUSTOMER_MESSAGE","maxExchanges",6,"maxCandidates",6,"maxModelCalls",32,"maxTokens",600000,"maxCostUsd",maxCost(),"timeoutSeconds",600);}
    private static String principal(String customer){return "customer:"+customer;}
    private static String identity(String customer,int version,String session){return hash(List.of("customer-personalization",customer,version,session));}
    private int profile(String customer){var c=engine.get("customers/"+customer);int version=0;for(var p:c.path("profiles"))version=Math.max(version,p.path("version").asInt());require(version>0,"PROFILE_REQUIRED","Saved customer profile required");return version;}
    /** One idempotent run per saved profile and login session prevents duplicate automatic/manual starts. */
    public synchronized JsonNode create(JsonNode body,String customer){
        fields(body,"profileVersion","sessionId","message");String session=text(body,"sessionId");require(session.matches("[A-Za-z0-9_-]{16,80}"),"SESSION_REQUIRED","Active login session required");sessionActive(customer,session);String intent=body.path("message").asText("").trim();require(intent.length()<=6000,"MESSAGE_LIMIT","Message too long");if(intent.isEmpty())intent="My profile was updated. Help me explore whether the available offers could better meet my needs.";require(customer.matches("[A-Za-z0-9_.:-]{1,120}")&&body.path("profileVersion").isIntegralNumber(),"CUSTOMER_SCOPE","Customer and profile version required");int version=body.path("profileVersion").asInt();require(profile(customer)==version,"STALE_PROFILE","Profile changed; reload your information");
        String id=identity(customer,version,session);var prior=store.find("WORKFLOW",id);if(prior.isPresent())return customerView(customer,id);
        for(var old:list(principal(customer),null,100))if((activeStates().contains(old.data().path("state").asText())||old.data().path("state").asText().equals("NEEDS_INPUT"))&&old.data().path("sessionId").asText().equals(session))cancel(old.id(),principal(customer));
        var budget=new Store.Budget(7,32,600000,maxCost(),Instant.now().plusSeconds(600).toString(),1,0,5,1,0,0);
        var data=obj("id",id,"analyst",principal(customer),"kind","PERSONALIZATION","network",network,"customerId",customer,"profileVersion",version,"sessionId",session,"intent",intent,"revision",1,"state",initialState(),"createdAt",Instant.now().toString(),"budget",budget,"steps",obj(),"evaluations",List.of(),"messages",body.path("message").asText("").isBlank()?List.of():List.of(obj("role","customer","text",intent)),"answers",List.of(),"clarificationRounds",0);
        store.budget(id,budget);store.create("WORKFLOW",id,data);return customerView(customer,id);
    }
    private void sessionActive(String customer,String session){engine.get("offer-creation/customers/"+customer+"/personalization-sessions?sessionId="+session);}
    public JsonNode latest(String customer,int version,String session){sessionActive(customer,session);var saved=store.find("WORKFLOW",identity(customer,version,session));return saved.isEmpty()?obj("state","NOT_STARTED","limits",limits()):customerView(customer,saved.get().id());}
    public JsonNode scopedView(String customer,String id,String session){require(get(id,principal(customer)).data().path("sessionId").asText().equals(session),"SESSION_SCOPE","Workflow belongs to another login session");sessionActive(customer,session);return customerView(customer,id);}
    /** Returns only this customer's workflow and marks stale versions unusable before exposing navigation. */
    public JsonNode customerView(String customer,String id){var saved=get(id,principal(customer));var w=saved.data();if(profile(customer)!=w.path("profileVersion").asInt()){if(!w.path("state").asText().equals("CANCELED"))cancel(id,principal(customer));return obj("id",id,"state","STALE_PROFILE","profileVersion",w.path("profileVersion"),"actions",List.of(),"message","Your profile changed. Find an offer using the latest information.");}
        if(w.path("state").asText().equals("COMPLETED"))try{engine.get("offer-creation/customers/"+customer+"/session-offers/"+w.at("/issued/id").asText()+"?sessionId="+w.path("sessionId").asText());}
        catch(Fault unavailable){if(unavailable.status!=404&&unavailable.status!=409)throw unavailable;return obj("id",id,"state","OFFER_CHANGED","actions",List.of(),"summary","This personalized quote is no longer available. Your saved selection and other valid offers remain available.");}
        return obj("id",id,"version",saved.version(),"state",w.path("state"),"profileVersion",w.path("profileVersion"),"summary",w.hasNonNull("summary")?w.path("summary"):w.at("/outcome/detail"),"offer",w.at("/issued/offer"),"quote",w.path("issued"),"messages",w.path("messages"),"assumptions",w.path("assumptions"),"conversation",PersonalizationConversation.context(w),"assessment",w.path("assessment"),"qualification",w.path("qualified"),"actions",w.path("actions"),"pendingQuestion",w.path("pendingQuestion"),"error",w.path("error"),"limits",limits(),"usage",store.budget(id));}
    public JsonNode cancelCustomer(String customer,String id){get(id,principal(customer));cancel(id,principal(customer));return customerView(customer,id);}
    public JsonNode reply(String customer,String id,JsonNode body){require(body.hasNonNull("questionId"),"QUESTION_ID_REQUIRED","Reply to the pending customer question");get(id,principal(customer));message(id,principal(customer),body);return customerView(customer,id);}
    private void fresh(String id){var w=current(id);try{sessionActive(w.path("customerId").asText(),w.path("sessionId").asText());}catch(Fault closed){if(closed.status==409||closed.status==404)cancel(id,w.path("analyst").asText());throw closed;}if(profile(w.path("customerId").asText())!=w.path("profileVersion").asInt()){cancel(id,w.path("analyst").asText());throw new Fault(409,"STALE_PROFILE","Customer changed; stale personalization canceled");}require(Instant.now().isBefore(Instant.parse(w.at("/budget/deadline").asText())),"DEADLINE","Personalization time limit reached; existing valid offers remain available");}
    private boolean noImprovement(String id,Result result){if(!result.decision().status().equals("NO_IMPROVEMENT"))return false;mutate(id,n->{n.put("state","NO_QUALIFIED_IMPROVEMENT").put("summary",result.decision().summary());n.set("assessment",tree(result.decision().planningAssessment()));});return true;}
    /** Waits for current qualified offers independently of outbound delivery, then reviews candidates with replay-safe stage identities. */
    protected void executeState(String id,String state){if(!state.equals(initialState()))return;fresh(id);var w=current(id);String customer=w.path("customerId").asText(),base="offer-creation/customers/"+customer+"/";
        if(!w.has("context")){var context=engine.get(base+"personalization-context?profileVersion="+w.path("profileVersion").asInt());String pipeline=context.at("/pipeline/state").asText();
            if(Set.of("NO_ELIGIBLE_OFFERS","ATTENTION_REQUIRED","STOPPED").contains(pipeline)){mutate(id,n->n.put("state","NO_QUALIFIED_IMPROVEMENT").put("summary","Your updated profile has no completed eligible offer set to personalize. Review the application status and existing offers."));return;}
            boolean qualificationFinished=pipeline.isEmpty()||Set.of("OFFERS_PENDING","COMPLETED").contains(pipeline);
            if(!qualificationFinished||!context.path("offersReady").asBoolean(true)||context.path("offers").isEmpty()){mutate(id,n->n.put("summary","Waiting for qualification and offer preparation to finish."));return;}mutate(id,n->n.set("context",context));}
        w=current(id);var context=obj("customer",w.path("context"),"profileVersion",w.path("profileVersion"),"stage","INITIAL_REVIEW");
        for(var stage:List.of(Role.ORCHESTRATOR,Role.CONTEXT)){fresh(id);var result=autonomousAgent(id,stage,"customer-"+stage.name().toLowerCase(Locale.ROOT),context);if(noImprovement(id,result)||stopIfUnresolved(id,result))return;context.set(stage==Role.ORCHESTRATOR?"personalizationPlan":"contextAnalysis",tree(result.decision()));}
        if(!current(id).has("qualified"))for(int attempt=0;attempt<6;attempt++){
            fresh(id);var input=context.deepCopy();input.put("stage","DESIGN");input.set("previousCandidates",current(id).path("attempts"));input.set("customerFeedback",current(id).path("candidateReviews"));
            var designed=autonomousAgent(id,Role.DESIGNER,"customer-design-"+attempt,input);if(noImprovement(id,designed)||stopIfUnresolved(id,designed))return;
            var proposal=designed.decision().customerProposal();require(proposal!=null,"CANDIDATE_REQUIRED","Designer must return exact customer terms");JsonNode selected=null;for(var item:current(id).at("/context/offers"))if(item.at("/response/id").asText().equals(proposal.responseId()))selected=item;
            require(selected!=null,"CUSTOMER_SCOPE","Candidate must use a currently qualified offer for this customer");
            String key="CP-"+hash(List.of(id,attempt,proposal)).substring(0,60);var request=obj("requestId",key,"responseId",proposal.responseId(),"responseVersion",selected.at("/response/version"),"policyVersion",selected.path("policyVersion"),"terms",proposal.terms(),"profileVersion",w.path("profileVersion"),"customerPolicyVersion",current(id).at("/context/personalizationPolicy/version"),"ruleChanges",proposal.ruleChanges());
            JsonNode result=current(id).path("attempts").path(attempt);
            if(result.isMissingNode()){fresh(id);try{result=engine.post(base+"personalization-candidates",request);}catch(Fault invalid){if(invalid.status!=422)throw invalid;result=obj("approved",false,"rejection",invalid.getMessage());}
                final var evaluation=result;mutate(id,n->n.withArray("attempts").add(evaluation));}
            if(!result.at("/approval/approved").asBoolean()||!result.path("improvesFit").asBoolean())continue;
            var reviewInput=context.deepCopy();reviewInput.put("stage","CANDIDATE_REVIEW").put("remainingCandidates",5-attempt);reviewInput.set("candidate",result);reviewInput.set("previousCandidates",current(id).path("attempts"));
            var review=agent(id,Role.ORCHESTRATOR,"customer-review-"+attempt,reviewInput);
            final var decision=tree(review.decision());mutate(id,n->n.withObject("/candidateReviews").set("candidate-"+review.stepId(),decision));
            if(noImprovement(id,review))return;
            if(review.decision().status().equals("REVISE"))continue;
            if(stopIfUnresolved(id,review))return;final var approved=result;mutate(id,n->{n.set("qualified",approved);n.set("assessment",tree(review.decision().planningAssessment()));});break;
        }
        w=current(id);if(!w.has("qualified")){mutate(id,n->n.put("state","NO_QUALIFIED_IMPROVEMENT").put("summary","No suitable qualified improvement was finalized within the candidate limit. Your currently valid offers remain available."));return;}
        var check=autonomousAgent(id,Role.ELIGIBILITY,"customer-qualification",obj("qualification",w.path("qualified"),"profileVersion",w.path("profileVersion")));if(stopIfUnresolved(id,check))return;
        fresh(id);if(!current(id).has("issued")){var issued=engine.post(base+"session-offers?sessionId="+w.path("sessionId").asText(),obj("approvalId",w.at("/qualified/approval/id"),"profileVersion",w.path("profileVersion")));mutate(id,n->n.set("issued",issued));}
        var guide=autonomousAgent(id,Role.GUIDE,"customer-guidance",obj("issued",current(id).path("issued"),"metrics",current(id).path("qualified"),"contextAnalysis",context.path("contextAnalysis"),"steps",List.of("OPEN_OFFER","REVIEW_TERMS","CUSTOMER_CONFIRM")));if(stopIfUnresolved(id,guide))return;
        fresh(id);String offer=current(id).at("/issued/id").asText();mutate(id,n->{n.put("state","COMPLETED").put("summary",guide.decision().summary());n.withArray("messages").add(obj("role","assistant","text",guide.decision().summary()));n.set("actions",tree(List.of(obj("type","OPEN_OFFER","offerId",offer),obj("type","REVIEW_TERMS","offerId",offer),obj("type","CUSTOMER_CONFIRM","offerId",offer))));});
    }
    /** Read tools never evaluate, issue or accept; commands are admitted by the workflow at explicit stages. */
    public JsonNode tool(Role role,Request request,String name,JsonNode args){fields(args);authorizeTool(role,request);var w=current(request.workflowId());return switch(name){
        case "customer.context" -> {require(role==Role.CONTEXT,"TOOL_DENIED","Customer context role required");var c=w.path("context");yield obj("customerId",c.path("customerId"),"profileVersion",c.path("profileVersion"),"currentFeatures",c.path("currentFeatures"),"previousProfile",c.path("previousProfile"),"previousOffers",c.path("previousOffers"));}
        case "personalization.limits" -> {require(role==Role.DESIGNER,"TOOL_DENIED","Designer role required");yield w.at("/context/personalizationPolicy");}
        case "qualification.result" -> {require(role==Role.ELIGIBILITY,"TOOL_DENIED","Qualification role required");yield w.path("qualified");}
        case "journey.steps" -> {require(role==Role.GUIDE,"TOOL_DENIED","Guide role required");yield obj("offer",w.at("/issued/offer"),"quote",w.path("issued"),"messages",w.path("messages"),"assumptions",w.path("assumptions"),"conversation",PersonalizationConversation.context(w),"assessment",w.path("assessment"),"steps",List.of("OPEN_OFFER","REVIEW_TERMS","CUSTOMER_CONFIRM"));}
        default -> throw new Fault(422,"TOOL_DENIED","Unknown customer tool");};}
}

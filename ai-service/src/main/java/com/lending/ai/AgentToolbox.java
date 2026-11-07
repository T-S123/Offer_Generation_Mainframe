package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Role-aware investigation and customer-personalization tools read frozen evidence and deterministic statistics; customer roles use owner policy limits instead of the simulation registry. */
public final class AgentToolbox implements AgentRunner.Tools {
    @FunctionalInterface public interface Reservation { void reserve(Request request,String callId,long tokens); }
    @FunctionalInterface public interface Settlement { void settle(Request request,String callId,long tokens); }
    private Settlement settlement=(r,c,t)->{};public AgentToolbox settlement(Settlement value){settlement=value;return this;}
    private Http policyReader,investigation,personalization;
    public AgentToolbox personalization(Http client){personalization=client;return this;}
    public AgentToolbox investigation(Http client){investigation=client;return this;}
    public AgentToolbox policyReader(Http reader){policyReader=reader;return this;}
    private final PolicyIndex policies;private final McpTools mcp;private final Reservation budget;
    public AgentToolbox(PolicyIndex policies,McpTools mcp,Reservation budget){this.policies=policies;this.mcp=mcp;this.budget=budget;}
    /** Retrieves current approved evidence without requiring an analyst to paste it into the conversation. */
    public List<JsonNode> initialObservations(Role role,Request request){
        if(personalization!=null){var observations=new ArrayList<JsonNode>();for(String name:personalizationNames(role))observations.add(obj("tool",name,"automatic",true,"result",call(role,request,new ToolCall(name,"{}"))));return observations;}
        if(investigation!=null){var names=investigationNames(role);var observations=new ArrayList<JsonNode>();for(String name:names)observations.add(obj("tool",name,"automatic",true,"result",call(role,request,new ToolCall(name,"{}"))));return observations;}
        if(role!=Role.RESEARCH)return List.of();
        var terms=new LinkedHashSet<String>();
        for(var parameter:request.payload().path("allowedParameterIds"))
            Collections.addAll(terms,parameter.asText().replaceAll("([a-z])([A-Z])","$1 $2").split("[^A-Za-z0-9]+"));
        String query=String.join(" ",terms).trim();
        if(query.isBlank())query=request.payload().path("intent").asText("policy");
        if(query.length()>1000)query=query.substring(0,1000);
        var args=obj("query",query,"product",request.payload().path("product").asText());
        return List.of(obj("tool","policy.search","automatic",true,"result",call(role,request,new ToolCall("policy.search",write(args)))));
    }
    public JsonNode descriptors(Role role){
        var list=new ArrayList<Object>();
        if(personalization==null)list.add(obj("name","registry.describe","inputSchema",Contracts.structure(),"sideEffect","READ","description","Get the 42 canonical parameters and technical domains"));
        if(role==Role.RESEARCH)list.add(obj("name","policy.search","inputSchema",Contracts.structure("query",Contracts.type("string"),"product",Contracts.type("string")),"sideEffect","READ","description","Search approved local policy excerpts and citation IDs"));
        if(investigation!=null)for(String name:investigationNames(role))list.add(obj("name",name,"inputSchema",Contracts.structure(),"sideEffect","READ","description","Read deterministic evidence for this registered investigation and its frozen scope"));
        if(personalization!=null)for(String name:personalizationNames(role))list.add(obj("name",name,"inputSchema",Contracts.structure(),"sideEffect","READ","description","Read evidence for this customer and saved profile only"));
        for(var d:mcp.descriptors(role))list.add(d);
        return tree(list);
    }
    public JsonNode call(Role role,Request request,ToolCall tool){
        var args=read(tool.argumentsJson());
        if(personalization!=null&&personalizationNames(role).contains(tool.name())){fields(args);return personalization.call("POST","internal/personalization-tools",obj("request",request,"name",tool.name(),"arguments",args));}
        if(investigation!=null&&investigationNames(role).contains(tool.name())){fields(args);return investigation.call("POST","internal/investigation-tools",obj("request",request,"name",tool.name(),"arguments",args));}
        if(tool.name().equals("registry.describe")){require(personalization==null,"TOOL_DENIED","Customer design uses personalization.limits");fields(args);return obj("parameters",new ParameterRegistry().all(),"source","ENGINE_TECHNICAL_CONSTRAINTS");}
        if(tool.name().equals("policy.search")){
            require(role==Role.RESEARCH&&(policies!=null||policyReader!=null),"TOOL_DENIED","Policy search is restricted to Research");
            fields(args,"query","product");String product=text(args,"product");
            require(product.equals(request.payload().path("product").asText()),"SCOPE_VIOLATION","Research product must match the request");
            if(policyReader!=null)return policyReader.call("POST","api/v1/ai/policies/search",args);
            policies.scan();
            return obj("toolReceiptId",id(),"evidence",policies.search(text(args,"query"),product,8),"sourceClass","APPROVED_INTERNAL_POLICY");
        }
        String[] parts=tool.name().split("\\.",2);require(parts.length==2,"TOOL_DENIED","Unknown tool");
        return mcp.call(role,parts[0],parts[1],args);
    }
    private static List<String> personalizationNames(Role role){return switch(role){case CONTEXT->List.of("customer.context");case DESIGNER->List.of("personalization.limits");case ELIGIBILITY->List.of("qualification.result");case GUIDE->List.of("journey.steps");default->List.of();};}
    private static List<String> investigationNames(Role role){return switch(role){case RESEARCH->List.of("performance.evidence");case ELIGIBILITY->List.of("eligibility.trace");case ANALYZER->List.of("statistics.measure","statistics.compare");case REFLECTION->List.of("evidence.review");default->List.of();};}
    public void settle(Request r,String id,long tokens){settlement.settle(r,id,tokens);}
    public void reserve(Request request,String callId,long maxTokens){budget.reserve(request,callId,maxTokens);}
}


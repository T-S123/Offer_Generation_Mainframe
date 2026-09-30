package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Role-aware local and MCP tools with automatic approved-policy retrieval and central budget accounting. */
public final class AgentToolbox implements AgentRunner.Tools {
    @FunctionalInterface public interface Reservation { void reserve(Request request,String callId,long tokens); }
    @FunctionalInterface public interface Settlement { void settle(Request request,String callId,long tokens); }
    private Settlement settlement=(r,c,t)->{};public AgentToolbox settlement(Settlement value){settlement=value;return this;}
    private final PolicyIndex policies;private final McpTools mcp;private final Reservation budget;
    public AgentToolbox(PolicyIndex policies,McpTools mcp,Reservation budget){this.policies=policies;this.mcp=mcp;this.budget=budget;}
    /** Retrieves current approved evidence without requiring an analyst to paste it into the conversation. */
    public List<JsonNode> initialObservations(Role role,Request request){
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
        list.add(obj("name","registry.describe","inputSchema",Contracts.structure(),"sideEffect","READ","description","Get the 42 canonical parameters and technical domains"));
        if(role==Role.RESEARCH)list.add(obj("name","policy.search","inputSchema",Contracts.structure("query",Contracts.type("string"),"product",Contracts.type("string")),"sideEffect","READ","description","Search approved local policy excerpts and citation IDs"));
        for(var d:mcp.descriptors(role))list.add(d);
        return tree(list);
    }
    public JsonNode call(Role role,Request request,ToolCall tool){
        var args=read(tool.argumentsJson());
        if(tool.name().equals("registry.describe")){fields(args);return obj("parameters",new ParameterRegistry().all(),"source","ENGINE_TECHNICAL_CONSTRAINTS");}
        if(tool.name().equals("policy.search")){
            require(role==Role.RESEARCH&&policies!=null,"TOOL_DENIED","Policy search is restricted to Research");
            fields(args,"query","product");String product=text(args,"product");
            require(product.equals(request.payload().path("product").asText()),"SCOPE_VIOLATION","Research product must match the request");
            policies.scan();
            return obj("toolReceiptId",id(),"evidence",policies.search(text(args,"query"),product,8),"sourceClass","APPROVED_INTERNAL_POLICY");
        }
        String[] parts=tool.name().split("\\.",2);require(parts.length==2,"TOOL_DENIED","Unknown tool");
        return mcp.call(role,parts[0],parts[1],args);
    }
    public void settle(Request r,String id,long tokens){settlement.settle(r,id,tokens);}
    public void reserve(Request request,String callId,long maxTokens){budget.reserve(request,callId,maxTokens);}
}


package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Role-aware local and MCP tools plus a central resource reservation callback. */
public final class AgentToolbox implements AgentRunner.Tools {
    @FunctionalInterface public interface Reservation { void reserve(Request request,String callId,long tokens); }
    @FunctionalInterface public interface Settlement { void settle(Request request,String callId,long tokens); }
    private Settlement settlement=(r,c,t)->{};public AgentToolbox settlement(Settlement value){settlement=value;return this;}
    private final PolicyIndex policies;private final McpTools mcp;private final Reservation budget;
    public AgentToolbox(PolicyIndex policies,McpTools mcp,Reservation budget){this.policies=policies;this.mcp=mcp;this.budget=budget;}
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
            return obj("toolReceiptId",id(),"evidence",policies.search(text(args,"query"),product,8),"sourceClass","APPROVED_INTERNAL_POLICY");
        }
        String[] parts=tool.name().split("\\.",2);require(parts.length==2,"TOOL_DENIED","Unknown tool");
        return mcp.call(role,parts[0],parts[1],args);
    }
    public void settle(Request r,String id,long tokens){settlement.settle(r,id,tokens);}
    public void reserve(Request request,String callId,long maxTokens){budget.reserve(request,callId,maxTokens);}
}


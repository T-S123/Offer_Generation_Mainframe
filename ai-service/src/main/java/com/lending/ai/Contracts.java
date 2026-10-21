package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.lending.ai.Json.*;

/** Versioned A2A envelopes expose typed material questions, follow-up intent and separate policy/metric citations. */
public final class Contracts {
    private Contracts(){}
    public enum Role { ORCHESTRATOR, RESEARCH, DESIGNER, COORDINATOR, ANALYZER, REFLECTION }
    public record Request(String schemaVersion,String requestId,String workflowId,String stepId,int planRevision,String deadline,JsonNode payload) {
        public void validate(){
            require("1.0.0".equals(schemaVersion)&&requestId!=null&&requestId.matches("[A-Za-z0-9._:-]{1,120}")&&workflowId!=null&&workflowId.matches("[A-Za-z0-9._:-]{1,120}"),"INVALID_ENVELOPE","Valid version and request/workflow IDs required");
            require(stepId!=null&&stepId.length()<=100&&planRevision>=1&&payload!=null&&payload.isObject()&&write(payload).length()<=150000,"INVALID_ENVELOPE","Invalid bounded payload");
            require(java.time.Instant.parse(deadline).isAfter(java.time.Instant.now()),"DEADLINE","Request has expired");
        }
    }
    public record ToolCall(String name,String argumentsJson) {}
    public record Claim(String kind,String text,List<String> evidenceIds,List<String> metricIds) {}
    public record Question(String decisionKey,String topic,String prompt,String why,List<String> choices,List<String> metricIds) {}
    public record Decision(String status,String summary,String clarification,List<String> allowedParameterIds,List<String> lockedStages,List<ParameterRegistry.Range> ranges,List<Claim> claims,ToolCall toolCall,Question question,String followUpIntent) {
        public Decision(String status,String summary,String clarification,List<String> allowedParameterIds,List<String> lockedStages,List<ParameterRegistry.Range> ranges,List<Claim> claims,ToolCall toolCall){this(status,summary,clarification,allowedParameterIds,lockedStages,ranges,claims,toolCall,null,null);}
    }
    public record Result(String schemaVersion,String kind,String workflowId,String requestId,String stepId,int planRevision,Decision decision,List<JsonNode> observations,String modelId,String promptVersion,String producedAt) {}
    static ObjectNode type(String name){return obj("type",name);}
    static ObjectNode nullable(String name){return obj("type",List.of(name,"null"));}
    static ObjectNode array(JsonNode items,int max){return obj("type","array","items",items,"maxItems",max);}
    static ObjectNode structure(Object... props){var p=obj(props);var names=new ArrayList<String>();p.fieldNames().forEachRemaining(names::add);return obj("type","object","properties",p,"required",names,"additionalProperties",false);}
    public static ObjectNode decisionSchema(){
        var strings=array(type("string"),42);
        var value=obj("anyOf",List.of(type("number"),type("string"),type("boolean")));
        var range=structure("parameterId",type("string"),"low",nullable("number"),"high",nullable("number"),"step",nullable("number"),
            "values",obj("anyOf",List.of(array(value,64),type("null"))),"evidenceIds",array(type("string"),24),"allowSentinel",type("boolean"));
        var claim=structure("kind",obj("type","string","enum",List.of("MEASURED","SIMULATED","ASSOCIATION","HYPOTHESIS")),"text",type("string"),
            "evidenceIds",strings.deepCopy().put("description","Policy citation IDs from citationContract.evidenceIds only. Run and candidate IDs belong in metricIds."),
            "metricIds",strings.deepCopy().put("description","Computed run, candidate, snapshot or metric IDs from citationContract.metricIds. Required nonempty for factual claims."));
        var tool=structure("name",type("string"),"argumentsJson",type("string"));
        var question=structure("decisionKey",type("string"),"topic",obj("type","string","enum",List.of("SCOPE","TRADEOFF","NEXT_EXPERIMENT","INTERPRETATION")),
            "prompt",type("string"),"why",type("string"),"choices",array(type("string"),4),"metricIds",array(type("string"),8));
        return structure("status",obj("type","string","enum",List.of("READY","BLOCKED","REVISE","TOOL","NEEDS_INPUT","FOLLOW_UP")),"summary",type("string"),
            "clarification",type("null"),"allowedParameterIds",strings,"lockedStages",array(type("string"),4),
            "ranges",array(range,42),"claims",array(claim,30),"toolCall",obj("anyOf",List.of(tool,type("null"))),
            "question",obj("anyOf",List.of(question,type("null"))),"followUpIntent",nullable("string"));
    }
    public static Decision decision(JsonNode raw){
        var d=convert(raw,Decision.class);
        require(Set.of("READY","BLOCKED","REVISE","TOOL","NEEDS_INPUT","FOLLOW_UP").contains(d.status())&&d.summary()!=null&&d.summary().length()<=12000&&d.allowedParameterIds()!=null&&d.lockedStages()!=null&&d.ranges()!=null&&d.claims()!=null,"INVALID_MODEL_OUTPUT","Required decision fields missing");
        require(d.status().equals("NEEDS_INPUT")== (d.question()!=null),"INVALID_QUESTION","NEEDS_INPUT requires a focused structured question");
        if(d.question()!=null){var q=d.question();
            require(q.decisionKey()!=null&&q.decisionKey().matches("[a-z][a-z0-9_-]{2,79}")&&Set.of("SCOPE","TRADEOFF","NEXT_EXPERIMENT","INTERPRETATION").contains(q.topic()),"INVALID_QUESTION","A material decision key and topic are required");
            require(q.prompt()!=null&&!q.prompt().isBlank()&&q.prompt().length()<=1200&&q.why()!=null&&!q.why().isBlank()&&q.why().length()<=1000,"INVALID_QUESTION","Ask one concise question and explain its effect");
            require(q.choices()!=null&&q.choices().size()>=2&&q.choices().size()<=4&&q.choices().stream().allMatch(c->c!=null&&!c.isBlank()&&c.length()<=300)&&q.metricIds()!=null&&q.metricIds().size()<=8,"INVALID_QUESTION","Supply 2-4 answerable choices and supplied metric references");
        }
        require(d.status().equals("FOLLOW_UP")== (d.followUpIntent()!=null),"INVALID_MODEL_OUTPUT","FOLLOW_UP requires revised experiment intent");
        if(d.followUpIntent()!=null)require(!d.followUpIntent().isBlank()&&d.followUpIntent().length()<=6000,"INVALID_MODEL_OUTPUT","Invalid follow-up intent");
        require(d.allowedParameterIds().size()<=42&&d.ranges().size()<=42&&d.claims().size()<=30,"INVALID_MODEL_OUTPUT","Decision too large");
        require(d.status().equals("TOOL")== (d.toolCall()!=null),"INVALID_MODEL_OUTPUT","Tool status and tool request disagree");
        if(d.toolCall()!=null)require(d.toolCall().argumentsJson()!=null&&d.toolCall().argumentsJson().length()<=16000,"INVALID_MODEL_OUTPUT","Tool arguments too large");
        var registry=new ParameterRegistry();d.allowedParameterIds().forEach(registry::get);d.ranges().forEach(r->registry.get(r.parameterId()));
        require(Set.of("offer","underwriting","marketing","bureau").containsAll(d.lockedStages()),"INVALID_MODEL_OUTPUT","Unknown stage lock");
        for(var c:d.claims())require(Set.of("MEASURED","SIMULATED","ASSOCIATION","HYPOTHESIS").contains(c.kind())&&c.text()!=null&&c.text().length()<=4000&&c.evidenceIds()!=null&&c.metricIds()!=null,"INVALID_CLAIM","Invalid claim");
        return new Decision(d.status(),d.summary(),null,d.allowedParameterIds(),d.lockedStages(),d.ranges(),d.claims(),d.toolCall(),d.question(),d.followUpIntent());
    }
}

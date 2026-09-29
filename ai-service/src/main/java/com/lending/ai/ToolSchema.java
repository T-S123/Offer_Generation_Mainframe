package com.lending.ai;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.lending.ai.Json.*;

/** Validates the documented bounded MCP schema subset locally before any tool request leaves the agent. */
final class ToolSchema {
    private ToolSchema(){}
    private static final Set<String> KEYS=Set.of("type","properties","required","additionalProperties","items","enum","minimum","maximum","minLength","maxLength","minItems","maxItems","description","title","default","$schema");
    static void validate(JsonNode schema,JsonNode value){check(schema,value,0);}
    private static void check(JsonNode s,JsonNode v,int depth){
        require(depth<=12&&s.isObject(),"MCP_SCHEMA_UNSUPPORTED","Schema must be a bounded object");
        s.fieldNames().forEachRemaining(k->require(KEYS.contains(k),"MCP_SCHEMA_UNSUPPORTED","Unsupported MCP schema keyword: "+k));
        String type=text(s,"type");boolean valid=switch(type){
            case "object"->v.isObject();case "array"->v.isArray();case "string"->v.isTextual();
            case "integer"->v.isIntegralNumber();case "number"->v.isNumber();case "boolean"->v.isBoolean();case "null"->v.isNull();default->false;};
        require(valid,"MCP_ARGUMENTS","Tool argument has the wrong type");
        if(s.has("enum")){boolean found=false;for(var option:s.path("enum"))if(hash(option).equals(hash(v)))found=true;require(found,"MCP_ARGUMENTS","Argument is outside the allowed enum");}
        if(v.isObject()){
            for(var key:s.path("required"))require(v.has(key.asText()),"MCP_ARGUMENTS","Required tool argument missing: "+key.asText());
            v.fields().forEachRemaining(e->{var property=s.path("properties").get(e.getKey());if(property!=null)check(property,e.getValue(),depth+1);
                else if(s.path("additionalProperties").isObject())check(s.path("additionalProperties"),e.getValue(),depth+1);
                else require(!s.has("additionalProperties")||s.path("additionalProperties").asBoolean(),"MCP_ARGUMENTS","Unknown tool argument: "+e.getKey());});
        }
        if(v.isArray()){require(v.size()>=s.path("minItems").asInt(0)&&v.size()<=s.path("maxItems").asInt(1000),"MCP_ARGUMENTS","Tool array exceeds its bounds");for(var item:v)check(s.path("items"),item,depth+1);}
        if(v.isTextual())require(v.asText().length()>=s.path("minLength").asInt(0)&&v.asText().length()<=s.path("maxLength").asInt(16000),"MCP_ARGUMENTS","Tool string exceeds its bounds");
        if(v.isNumber())require((!s.has("minimum")||v.decimalValue().compareTo(s.path("minimum").decimalValue())>=0)&&(!s.has("maximum")||v.decimalValue().compareTo(s.path("maximum").decimalValue())<=0),"MCP_ARGUMENTS","Tool number exceeds its bounds");
    }
}

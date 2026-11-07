package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.lending.ai.Contracts.*;
import static com.lending.ai.Json.*;

/** Stable ownership for simulation, investigation and customer-personalization specialists, including legacy envelopes. */
public enum Network {
    SIMULATION, FEEDBACK, PERSONALIZATION;
    public List<Role> roles(){return this==PERSONALIZATION?List.of(Role.ORCHESTRATOR,Role.CONTEXT,Role.DESIGNER,Role.ELIGIBILITY,Role.GUIDE):this==SIMULATION?List.of(Role.ORCHESTRATOR,Role.RESEARCH,Role.DESIGNER,Role.COORDINATOR,Role.ANALYZER,Role.REFLECTION):List.of(Role.ORCHESTRATOR,Role.RESEARCH,Role.ELIGIBILITY,Role.ANALYZER,Role.REFLECTION);}
    public static Network parse(String value){try{return valueOf(value);}catch(Exception e){throw new Fault(422,"INVALID_NETWORK","Choose SIMULATION, FEEDBACK or PERSONALIZATION");}}
    public static Network workflow(JsonNode data){return data.hasNonNull("network")?parse(data.path("network").asText()):data.path("kind").asText().equals("FEEDBACK")?FEEDBACK:SIMULATION;}
    public static Network request(Request request){var p=request.payload();if(p.hasNonNull("network"))return parse(p.path("network").asText());
        return List.of("mode","analysisMode","reviewType").stream().anyMatch(k->p.path(k).asText().equals("FEEDBACK"))?FEEDBACK:SIMULATION;}
    public void accept(Role role,Request request){require(roles().contains(role)&&request(request)==this,"NETWORK_SCOPE","Delegation belongs to a different A2A network");}
}

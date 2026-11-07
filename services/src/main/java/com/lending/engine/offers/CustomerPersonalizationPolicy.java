package com.lending.engine.offers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lending.engine.infrastructure.Json;
import com.lending.engine.domain.Model.*;
import com.lending.engine.simulation.domain.BusinessRules;
import com.lending.engine.simulation.domain.BusinessRules.RuleSet;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Versioned customer-only permissions retain catalog authority; the initial policy permits bounded tightening, never exceptions. */
public final class CustomerPersonalizationPolicy {
    public static final String VERSION="CUSTOMER-2026-001";
    private CustomerPersonalizationPolicy(){}
    public static Object definition(){return Map.of("version",VERSION,"effectiveFrom","2026-01-01T00:00:00Z","effectiveUntil","2028-01-01T00:00:00Z","applicability","Currently qualified customer and exact saved profile version","rulePermissions",List.of(Map.of("parameterId","underwriting.maximumDtiPct","direction","DECREASE_ONLY","maximumChange",5),Map.of("parameterId","marketing.minimumTenureMonths","direction","INCREASE_ONLY","maximumChange",12)),"fixedConstraints",List.of("consent","optOut","suppression","bureauIdentity","importedDeclines","allOtherRuleParameters"));}
    /** Applies only explicitly permitted per-customer changes, leaving the catalog rule set untouched. */
    public static RuleSet apply(RuleSet original,Product product,JsonNode changes,String version,Instant now){
        if(!VERSION.equals(version)||now.isBefore(Instant.parse("2026-01-01T00:00:00Z"))||!now.isBefore(Instant.parse("2028-01-01T00:00:00Z")))throw new Problem(409,"Customer personalization policy changed or expired");
        if(changes==null||!changes.isArray()||changes.size()>2)throw new Problem(422,"At most two approved rule changes are allowed");
        RuleSet baseline=original==null?BusinessRules.defaults(product):original;
        ObjectNode copy=Json.MAPPER.valueToTree(baseline);var seen=new HashSet<String>();
        for(var change:changes){
            if(!change.isObject()||change.size()!=2||!change.has("parameterId")||!change.path("value").isIntegralNumber())throw new Problem(422,"A rule change requires parameterId and an integer value");
            String id=change.path("parameterId").asText();if(!seen.add(id))throw new Problem(422,"Duplicate rule change");
            int next=change.path("value").asInt();String[] path=id.split("\\.");
            if(id.equals("underwriting.maximumDtiPct")){int current=baseline.underwriting().maximumDtiPct();if(next<Math.max(0,current-5)||next>current)throw new Problem(422,"DTI change exceeds approved tightening limits");}
            else if(id.equals("marketing.minimumTenureMonths")){int current=baseline.marketing().minimumTenureMonths();if(next<current||next>Math.min(1200,current+12))throw new Problem(422,"Tenure change exceeds approved tightening limits");}
            else throw new Problem(422,"Rule parameter is fixed by the approved policy");
            ((ObjectNode)copy.path(path[0])).put(path[1],next);
        }
        RuleSet result=Json.MAPPER.convertValue(copy,RuleSet.class);BusinessRules.validate(result);return result;
    }
}

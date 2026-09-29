package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import static com.lending.ai.Json.*;

/** Technical domains for all 42 editable fields; engine bounds never substitute for policy evidence. */
public final class ParameterRegistry {
    public record Parameter(String id,String stage,String type,String unit,BigDecimal low,BigDecimal high,int scale,boolean autoOnly) {}
    public record Range(String parameterId,BigDecimal low,BigDecimal high,BigDecimal step,List<JsonNode> values,List<String> evidenceIds,boolean allowSentinel) {}
    private final Map<String,Parameter> entries=new LinkedHashMap<>();
    public ParameterRegistry(){
        add("offer","name","string","label",null,null,0,false);add("offer","active","boolean","boolean",null,null,0,false);
        for(String k:List.of("startsOn","endsOn"))add("offer",k,"date","date",null,null,0,false);
        add("offer","minimumAmountUsd","decimal","USD",".01","999999999.99",2,false);
        add("offer","maximumAmountUsd","decimal","USD",".01","999999999.99",2,false);
        add("offer","illustrativeAprPct","decimal","percent","0","100",2,false);
        add("offer","annualFeeUsd","decimal","USD/year","0","999999999.99",2,false);
        add("offer","termMonths","integer","months","0","120",0,false);
        for(String stage:List.of("underwriting","marketing","bureau")){
            add(stage,"minimumScore","integer","points","0","850",0,false);
            add(stage,"minimumIncomeUsd","decimal","USD/month","0","999999999",2,false);
            add(stage,"maximumDtiPct","integer","percent","0","999",0,false);
            add(stage,"maximumUtilizationPct","integer","percent","0","100",0,false);
            add(stage,"maximumDelinquencies","integer","count","0","99",0,false);
            add(stage,"maximumAmountUsd","decimal","USD","0","999999999",2,false);
            add(stage,"maximumLtvPct","integer","percent","1","9999",0,true);
            if(stage.equals("bureau")){
                add(stage,"maximumInquiries","integer","count","0","99",0,false);
                add(stage,"minimumHistoryMonths","integer","months","0","1200",0,false);
                add(stage,"maximumReportAgeDays","integer","days","0","99999",0,false);
                add(stage,"allowBankruptcy","boolean","boolean",null,null,0,false);
            }else{
                add(stage,"incomeMultiple","integer","multiple","1","999",0,false);
                add(stage,"maximumVehicleAge","integer","years","0","99",0,true);
                add(stage,"minimumTenureMonths","integer","months","0","1200",0,false);
                add(stage,"excludeExistingProduct","boolean","boolean",null,null,0,false);
            }
        }
    }
    private void add(String stage,String key,String type,String unit,String low,String high,int scale,boolean auto){
        String id=(stage.equals("offer")?"/offer/":"/rules/"+stage+"/")+key;
        entries.put(id,new Parameter(id,stage,type,unit,low==null?null:new BigDecimal(low),high==null?null:new BigDecimal(high),scale,auto));
    }
    public Collection<Parameter> all(){return List.copyOf(entries.values());}
    public Parameter get(String id){var p=entries.get(id);require(p!=null,"UNKNOWN_PARAMETER","Unsupported parameter: "+id);return p;}
    public void validateValue(String id,JsonNode value,String product,boolean allowSentinel){
        var p=get(id);require(!p.autoOnly()||product.equals("AUTO_LOAN"),"INACTIVE_PARAMETER",id+" only affects auto loans");
        switch(p.type()){
            case "boolean" -> require(value.isBoolean(),"INVALID_TYPE",id+" requires true/false");
            case "date" -> {try{require(value.isTextual(),"INVALID_DATE","Date required");LocalDate.parse(value.asText());}catch(Exception e){throw new Fault(422,"INVALID_DATE",id+" requires YYYY-MM-DD");}}
            case "string" -> require(value.isTextual()&&!value.asText().isBlank()&&value.asText().length()<=60,"INVALID_NAME","Name must contain 1-60 characters");
            default -> {
                require(value.isNumber(),"INVALID_TYPE",id+" requires a number");var v=value.decimalValue();
                require(v.stripTrailingZeros().scale()<=p.scale()&&v.compareTo(p.low())>=0&&v.compareTo(p.high())<=0,"OUT_OF_DOMAIN",id+" is outside its technical domain");
                if(id.endsWith("/minimumScore"))require(v.signum()==0?allowSentinel:v.compareTo(new BigDecimal("300"))>=0,"SENTINEL_PERMISSION","Score zero needs explicit permission; scores 1-299 are invalid");
                if(id.equals("/offer/termMonths"))require(product.equals("CREDIT_CARD")?v.signum()==0:v.intValue()>=1,"PRODUCT_DOMAIN","Card term must be zero; loan term must be positive");
            }
        }
    }
    /** Builds a bounded, rounded grid; disabled-score sentinels can only be explicit discrete values. */
    public List<JsonNode> levels(Range r,String product,JsonNode baseline,int count){
        get(r.parameterId());require(count>=2&&count<=10,"INVALID_GRID","Use 2-10 levels");
        var values=new ArrayList<JsonNode>();
        if(r.values()!=null&&!r.values().isEmpty()){
            require(r.values().size()<=64&&r.low()==null&&r.high()==null,"INVALID_DOMAIN","Discrete domains cannot also contain bounds");
            values.addAll(r.values());
        }else{
            var p=get(r.parameterId());require(p.low()!=null&&r.low()!=null&&r.high()!=null&&r.step()!=null,"INVALID_DOMAIN","Numeric bounds and step required");
            require(r.step().signum()>0&&r.step().stripTrailingZeros().scale()<=p.scale()&&r.low().compareTo(r.high())<=0,"INVALID_DOMAIN","Invalid range or precision");
            if(r.parameterId().endsWith("/minimumScore"))require(r.low().compareTo(new BigDecimal("300"))>=0,"SENTINEL_PERMISSION","Specify disabled scores as explicit values");
            for(int i=0;i<count;i++){
                var v=r.low().add(r.high().subtract(r.low()).multiply(BigDecimal.valueOf(i)).divide(BigDecimal.valueOf(count-1),12,RoundingMode.HALF_UP));
                v=r.low().add(v.subtract(r.low()).divide(r.step(),0,RoundingMode.HALF_UP).multiply(r.step())).min(r.high());
                values.add(tree(v));}
            values.add(tree(r.high()));
            if(baseline.isNumber()&&baseline.decimalValue().compareTo(r.low())>=0&&baseline.decimalValue().compareTo(r.high())<=0)values.add(baseline);
        }
        var unique=new LinkedHashMap<String,JsonNode>();
        for(var v:values){validateValue(r.parameterId(),v,product,r.allowSentinel());unique.put(hash(v),v);}
        require(!unique.isEmpty(),"EMPTY_DOMAIN","No values remain");return List.copyOf(unique.values());
    }
    /** Retains all business fields, including hidden controls, while dropping only server-owned rule metadata. */
    public static ObjectNode configuration(JsonNode draft){
        var n=obj("offer",draft.path("offer"),"rules",draft.path("rules"));var rules=(ObjectNode)n.path("rules");
        rules.remove(List.of("id","version","createdAt"));return (ObjectNode)canonical(n);
    }
    public static void set(ObjectNode root,String pointer,JsonNode value){
        String[] p=pointer.substring(1).split("/");JsonNode parent=root;
        for(int i=0;i<p.length-1;i++)parent=parent.path(p[i]);
        require(parent.isObject()&&parent.has(p[p.length-1]),"UNKNOWN_PARAMETER","Existing field required");
        ((ObjectNode)parent).set(p[p.length-1],canonical(value));
    }
    public static Set<String> differences(JsonNode baseline,JsonNode candidate){
        var changed=new TreeSet<String>();diff("",baseline,candidate,changed);return changed;
    }
    private static void diff(String path,JsonNode a,JsonNode b,Set<String> out){
        if(a.isObject()&&b.isObject()){var keys=new TreeSet<String>();a.fieldNames().forEachRemaining(keys::add);b.fieldNames().forEachRemaining(keys::add);for(String k:keys)diff(path+"/"+k,a.path(k),b.path(k),out);}
        else if(!canonical(a).equals(canonical(b)))out.add(path);
    }
    /** Rechecks the full unchanged-field invariant and cross-field constraints at every execution boundary. */
    public void validatePatch(ObjectNode baseline,ObjectNode candidate,Set<String> allowed,Set<String> locked){
        for(String p:differences(baseline,candidate)){
            var entry=get(p);require(allowed.contains(p)&&!locked.contains(entry.stage()),"SCOPE_VIOLATION","Unauthorized change: "+p);
        }
        var offer=candidate.path("offer");
        require(offer.path("minimumAmountUsd").decimalValue().compareTo(offer.path("maximumAmountUsd").decimalValue())<=0,"INVALID_AMOUNTS","Minimum amount exceeds maximum");
        require(!LocalDate.parse(offer.path("startsOn").asText()).isAfter(LocalDate.parse(offer.path("endsOn").asText())),"INVALID_DATES","Start follows end");
    }
}

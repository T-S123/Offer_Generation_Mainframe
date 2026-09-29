package com.lending.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.util.*;

/** Strict structured boundaries and canonical hashes shared by the six independently deployed agents. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private Json() {}
    public static final class Fault extends RuntimeException {
        public final int status;
        public final String code;
        public Fault(int status,String code,String message){super(message);this.status=status;this.code=code;}
    }
    public static void require(boolean valid,String code,String message){if(!valid)throw new Fault(422,code,message);}
    public static JsonNode read(String text){try{return MAPPER.readTree(text);}catch(Exception e){throw new Fault(422,"INVALID_JSON","Invalid JSON");}}
    public static <T>T convert(JsonNode n,Class<T> type){try{return MAPPER.treeToValue(n,type);}catch(Exception e){throw new Fault(422,"INVALID_FIELDS","Invalid "+type.getSimpleName()+" fields");}}
    public static String write(Object v){try{return MAPPER.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("Cannot encode structured data",e);}}
    public static JsonNode tree(Object v){return MAPPER.valueToTree(v);}
    public static ObjectNode object(){return MAPPER.createObjectNode();}
    public static ObjectNode obj(Object... pairs){var n=object();for(int i=0;i<pairs.length;i+=2)n.set((String)pairs[i],tree(pairs[i+1]));return n;}
    public static String text(JsonNode n,String key){var v=n.path(key);require(v.isTextual()&&!v.asText().isBlank(),"MISSING_FIELD",key+" is required");return v.asText();}
    public static void fields(JsonNode n,String... allowed){require(n.isObject(),"INVALID_FIELDS","Object required");Set<String> keys=Set.of(allowed);n.fieldNames().forEachRemaining(k->require(keys.contains(k),"UNKNOWN_FIELD","Unsupported field: "+k));}
    public static String hash(Object value){return digest(write(canonical(tree(value))).getBytes(StandardCharsets.UTF_8));}
    public static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    /** Sorts keys and normalizes decimal identity while preserving integral JSON wire values for strict engine fields. */
    public static JsonNode canonical(JsonNode n){
        if(n.isObject()){var o=object();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);keys.forEach(k->o.set(k,canonical(n.get(k))));return o;}
        if(n.isArray()){var a=MAPPER.createArrayNode();n.forEach(v->a.add(canonical(v)));return a;}
        if(n.isNumber()){var value=n.decimalValue().stripTrailingZeros();return value.scale()<=0?BigIntegerNode.valueOf(value.toBigIntegerExact()):DecimalNode.valueOf(value);}
        return n;
    }
    public static String id(){return UUID.randomUUID().toString();}
}

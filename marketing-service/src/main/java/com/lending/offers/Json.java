/** Strict local JSON codec; identifiers never become model features. */
package com.lending.offers;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;

/** Strict local JSON codec; identifiers never become model features. */
public final class Json {
    /** Prevents instantiation of this utility-only type. */
    private Json(){}
    public static final ObjectMapper MAPPER=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    /** Serializes a value using the shared strict JSON configuration. */
    public static String write(Object o){try{return MAPPER.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException("Cannot encode record",e);}}
    /** Converts a value to a JSON tree for field-based processing. */
    public static JsonNode tree(String s){try{return MAPPER.readTree(s);}catch(Exception e){throw new Fault(422,"Malformed JSON");}}
    /** Deserializes JSON into the requested type while rejecting unsupported payload shapes. */
    public static <T>T read(String s,Class<T> t){try{return MAPPER.readValue(s,t);}catch(Exception e){throw new IllegalStateException("Cannot decode record",e);}}
    /** Computes a SHA-256 fingerprint for stable identity and content comparison. */
    public static String hash(Object o){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(write(o).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    /** Groups fault behavior for JSON operations. */
    public static class Fault extends RuntimeException {public final int status;
        /** Creates an application failure carrying its HTTP status and diagnostic message. */
        public Fault(int status,String message){super(message);this.status=status;}}
}

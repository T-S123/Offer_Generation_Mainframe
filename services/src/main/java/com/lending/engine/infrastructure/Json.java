/**
 * Strict JSON boundary: reject duplicate/unknown fields and implicit scalar or fractional-integer
 * coercion.
 */
package com.lending.engine.infrastructure;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;

/**
 * Strict JSON boundary: reject duplicate/unknown fields and implicit scalar or fractional-integer
 * coercion.
 */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    /** Prevents instantiation of this utility-only type. */
    private Json() {}
    /** Serializes a value using the shared strict JSON configuration. */
    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode stored record", e); }
    }
    /** Deserializes JSON into the requested type while rejecting unsupported payload shapes. */
    public static <T> T read(String value, Class<T> type) {
        try { return MAPPER.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("Cannot decode stored record", e); }
    }
}

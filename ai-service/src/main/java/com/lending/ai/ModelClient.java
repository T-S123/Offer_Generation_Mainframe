package com.lending.ai;
import com.fasterxml.jackson.databind.JsonNode;

/** Injectable structured-model boundary; production uses Astra, while tests use explicit deterministic doubles. */
@FunctionalInterface
public interface ModelClient {
    record Reply(JsonNode output,String model,long inputTokens,long outputTokens) {}
    Reply complete(String instructions,JsonNode input,JsonNode schema);
}

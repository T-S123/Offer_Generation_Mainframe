/** Engine integration port: current qualification, synthetic features and authoritative variant decisions. */
package com.lending.offers.application;
import com.lending.offers.Json;
import com.fasterxml.jackson.databind.JsonNode;

/** Engine integration port: current qualification, synthetic features and authoritative variant decisions. */
public interface Source {
    /** Retrieves current qualification or feature data through the engine boundary. */
    JsonNode get(String route);
    /** Submits a variant-qualification request through the engine boundary. */
    JsonNode post(String route,Object value);
}

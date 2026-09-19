/**
 * Read-only repository HTTP port for customer/warehouse recovery and DMG execution; consumers never query
 * the marketing service's tables.
 */
package com.lending.engine.storage;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.infrastructure.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

/**
 * Read-only repository HTTP port for customer/warehouse recovery and DMG execution; consumers never query
 * the marketing service's tables.
 */
public final class StorageClient {
    private final int port;private final String token;private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    /** Initializes storage client with the supplied configuration and dependencies. */
    public StorageClient(int port,String token){this.port=port;this.token=token;}
    /** Retrieves repository data over authenticated HTTP with bounded request and response handling. */
    public JsonNode get(String path){try{var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/storage/"+path)).header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(10)).GET().build();var r=client.send(request,HttpResponse.BodyHandlers.ofString());if(r.statusCode()>=400)throw new Problem(r.statusCode(),"Offer storage unavailable");return Json.MAPPER.readTree(r.body());}catch(Problem e){throw e;}catch(Exception e){throw new Problem(503,"Offer storage unavailable");}}
}

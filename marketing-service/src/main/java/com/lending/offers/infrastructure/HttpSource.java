/** Authenticated local HTTP adapter; never connects directly to engine/customer tables. */
package com.lending.offers.infrastructure;
import com.lending.offers.Json;
import com.lending.offers.application.Source;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

/** Authenticated local HTTP adapter; never connects directly to engine/customer tables. */
public final class HttpSource implements Source {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();private final String base,token;
    /** Initializes HTTP source with the supplied configuration and dependencies. */
    public HttpSource(int port,String token){base="http://127.0.0.1:"+port+"/api/v1/offer-source/";this.token=token;}
    /** Retrieves JSON from an authenticated engine API endpoint. */
    public JsonNode get(String route){return call("GET",route,null);}
    /** Submits a JSON payload to an authenticated engine API endpoint. */
    public JsonNode post(String route,Object value){return call("POST",route,value);}
    /** Performs the authenticated HTTP exchange and maps unsuccessful responses to service faults. */
    private JsonNode call(String method,String route,Object body){try{var r=HttpRequest.newBuilder(URI.create(base+route)).header("Authorization","Bearer "+token).header("Content-Type","application/json").timeout(Duration.ofSeconds(60));if(method.equals("GET"))r.GET();else r.POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));var result=http.send(r.build(),HttpResponse.BodyHandlers.ofString());var value=Json.tree(result.body());if(result.statusCode()>=400)throw new Json.Fault(result.statusCode(),value.path("error").asText("Engine request failed"));return value;}catch(Json.Fault e){throw e;}catch(Exception e){throw new Json.Fault(503,"Engine API unavailable");}}
}

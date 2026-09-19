/** Terminal transport only: every screen command goes through the public HTTP API. */
package com.lending.engine.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.lending.engine.infrastructure.Json;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

/** Terminal transport only: every screen command goes through the public HTTP API. */
public final class ApiClient {
    private final URI base;
    private final String token;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    /** Initializes API client with the supplied configuration and dependencies. */
    public ApiClient(int port,String token){base=URI.create("http://127.0.0.1:"+port+"/api/v1/");this.token=token;}
    /** Sends a terminal command through the public HTTP API and decodes its JSON response. */
    public JsonNode call(String method,String path,Object body) {
        try {
            HttpRequest.Builder request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(120))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json");
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.write(body)));
            HttpResponse<String> response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            JsonNode result=Json.MAPPER.readTree(response.body());
            if(response.statusCode()>=400)throw new IllegalArgumentException(result.path("error").asText("API request failed"));
            return result;
        } catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalStateException("API unavailable; check engine console before retrying",e);}
    }
}

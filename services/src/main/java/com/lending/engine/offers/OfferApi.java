/**
 * Scoped offer-source API, including automatic training batches and hidden pre-screen snapshots, and
 * HTTP-only terminal proxy.
 */
package com.lending.engine.offers;

import com.lending.engine.api.ApiServer;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.infrastructure.Json;
import com.sun.net.httpserver.HttpExchange;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * Scoped offer-source API, including automatic training batches and hidden pre-screen snapshots, and
 * HTTP-only terminal proxy.
 */
public final class OfferApi {
    private final OfferSource source;private final byte[] sourceToken;private final String serviceToken;private final int port;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    /** Initializes offer API with the supplied configuration and dependencies. */
    public OfferApi(OfferSource source,String sourceToken,String serviceToken,int port){this.source=source;this.sourceToken=("Bearer "+sourceToken).getBytes(StandardCharsets.UTF_8);this.serviceToken=serviceToken;this.port=port;}
    /** Checks the scoped credential before exposing source or marketing-service operations. */
    public boolean authorized(String path,String auth){return path.startsWith("/api/v1/offer-source/")&&auth!=null&&MessageDigest.isEqual(sourceToken,auth.getBytes(StandardCharsets.UTF_8));}
    /** Dispatches the HTTP method and path to the corresponding application operation. */
    public Object route(HttpExchange x,String[] p,Map<String,String> q)throws Exception {
        String method=x.getRequestMethod();
        if(p[0].equals("offer-creation")){
            String suffix=x.getRequestURI().getRawPath().substring("/api/v1/offer-creation".length());String query=x.getRequestURI().getRawQuery();
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+suffix+(query==null?"":"?"+query))).timeout(Duration.ofSeconds(90)).header("Authorization","Bearer "+serviceToken).header("Content-Type","application/json");
            if(method.equals("GET"))request.GET();else if(method.equals("POST"))request.POST(HttpRequest.BodyPublishers.ofString(Json.write(ApiServer.body(x,com.fasterxml.jackson.databind.JsonNode.class))));else throw new Problem(404,"Unknown method");
            var result=http.send(request.build(),HttpResponse.BodyHandlers.ofString());var body=Json.MAPPER.readTree(result.body());if(result.statusCode()>=400)throw new Problem(result.statusCode(),body.path("error").asText("Offer service unavailable"));return body;
        }
        if(p.length==2&&method.equals("GET"))return switch(p[1]){
            case "policy"->source.policy();
            case "simulation-batches"->source.simulationBatches();
            case "responses"->source.responses(q.get("after"),Integer.parseInt(q.getOrDefault("limit","100")));
            case "prescreen"->source.prescreens(q.get("after"),Integer.parseInt(q.getOrDefault("limit","100")));
            case "features"->source.features(q.get("batchId"),Integer.parseInt(q.getOrDefault("offset","0")),Integer.parseInt(q.getOrDefault("limit","200")));
            default->throw new Problem(404,"Unknown source route");};
        if(p.length==3&&p[1].equals("contexts")&&method.equals("GET"))return source.context(p[2]);
        if(p.length==3&&p[1].equals("approvals")&&method.equals("GET"))return source.check(p[2]);
        if(p.length==2&&p[1].equals("variants")&&method.equals("POST"))return source.qualify(ApiServer.body(x,OfferSource.Proposal.class));
        throw new Problem(404,"Unknown offer source route");
    }
}

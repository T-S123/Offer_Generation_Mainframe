/**
 * Real HTTP bureau integration with a separate credential, bounded response size, deadline and response
 * correlation checks.
 */
package com.lending.engine.bureau.infrastructure;

import com.lending.engine.bureau.application.BureauPorts.CreditGateway;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.infrastructure.Json;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * Real HTTP bureau integration with a separate credential, bounded response size, deadline and response
 * correlation checks.
 */
public final class HttpCreditGateway implements CreditGateway {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base,token;
    private final Map<String,String> subjects=Collections.synchronizedMap(new LinkedHashMap<String,String>(100,0.75f,true){
        /** Evicts the oldest cache entry when the configured size limit is exceeded. */
        protected boolean removeEldestEntry(Map.Entry<String,String> entry){return size()>10000;}});
    /** Initializes HTTP credit gateway with the supplied configuration and dependencies. */
    public HttpCreditGateway(int port,String token){base="http://127.0.0.1:"+port+"/api/v1/credit/";this.token=token;}
    /** Sends an authenticated credit-service request with bounded time and response size. */
    public <T>T call(String method,String path,Object body,Class<T> type){try{
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;try(var stream=response.body()){bytes=stream.readNBytes(1024*1024+1);}if(bytes.length>1024*1024)throw new IllegalStateException("Bureau response too large");
        if(response.statusCode()>=500)throw new IllegalStateException("Bureau API unavailable");if(response.statusCode()>=400)throw new Problem(response.statusCode(),"Bureau API rejected request; check profile version and identifiers");return Json.MAPPER.readValue(bytes,type);
    }catch(Problem e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}catch(Exception e){throw new IllegalStateException("Bureau API unavailable",e);}}
    /** Loads the current independent bureau report through the credit-service API. */
    public BureauProfile profile(String customerId){var p=call("POST","profiles/"+customerId,Map.of(),BureauProfile.class);if(!p.customerId().equals(customerId))throw new IllegalStateException("Bureau customer correlation failed");return p;}
    /** Submits a version-checked bureau report import or edit to the credit-service API. */
    public BureauProfile change(String customerId,ProfileChange request){return call("PUT","profiles/"+customerId,request,BureauProfile.class);}
    /** Returns the opaque bureau subject identifier mapped to a customer. */
    public String subjectId(String customerId){String id=subjects.get(customerId);if(id!=null)return id;id=profile(customerId).subjectId();subjects.put(customerId,id);return id;}
    /** Retrieves independent bureau report history through the credit-service API. */
    public List<BureauProfile> history(String customerId,int after){return Arrays.asList(call("GET","profiles/"+customerId+"/history?after="+after,null,BureauProfile[].class));}
    /** Requests a credit decision and verifies its identity and source correlation. */
    public Assessment assess(CreditInput input){var a=call("POST","assessments",input,Assessment.class);
        if(!input.equals(a.input())||!input.requestId().equals(a.requestId())||!input.subjectId().equals(a.subjectId())||!input.customerId().equals(a.bureauProfile().customerId())||a.bureauProfileVersion()!=a.bureauProfile().version()||!Set.of("APPROVED","DECLINED","REVIEW").contains(a.outcome()))throw new IllegalStateException("Credit response correlation failed");return a;}
}

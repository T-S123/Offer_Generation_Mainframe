/** HTTP routing only; shared authentication precedes these marketing use cases. */
package com.lending.engine.marketing.api;

import com.lending.engine.api.ApiServer;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.marketing.application.MarketingEngine;
import com.lending.engine.marketing.domain.Marketing.*;
import com.sun.net.httpserver.HttpExchange;
import java.util.Map;

/** HTTP routing only; shared authentication precedes these marketing use cases. */
public final class MarketingApi {
    private final MarketingEngine engine;
    /** Initializes marketing API with the supplied configuration and dependencies. */
    public MarketingApi(MarketingEngine engine){this.engine=engine;}
    /** Dispatches the HTTP method and path to the corresponding application operation. */
    public Object route(HttpExchange x,String[] p,Map<String,String> q,int offset,int limit)throws java.io.IOException{
        String method=x.getRequestMethod();
        if(p.length<2)throw new Problem(404,"Marketing route not found");
        String resource=p[1];
        if(p.length==2){
            if(method.equals("GET"))return switch(resource){
                case "offers"->engine.offers();case "campaigns"->engine.campaigns();case "policy"->engine.policy();case "suppressions"->engine.suppressions();case "runs"->engine.runs(offset,limit);default->throw new Problem(404,"Resource not found");};
            if(resource.equals("policy") && method.equals("PUT"))return engine.savePolicy(ApiServer.body(x,PolicyUpdate.class));
            if(resource.equals("runs") && method.equals("POST"))return engine.preview(ApiServer.body(x,RunRequest.class));
        }
        if(p.length==3){
            String id=p[2];
            if(method.equals("GET"))return switch(resource){case "offers"->engine.offer(id);case "campaigns"->engine.campaign(id);case "runs"->engine.run(id);case "policy"->{if(!id.equals("history"))throw new Problem(404,"Route not found");yield engine.history("policy",null);}default->throw new Problem(404,"Resource not found");};
            if(method.equals("PUT"))return switch(resource){case "offers"->engine.saveOffer(id,ApiServer.body(x,OfferUpdate.class));case "campaigns"->engine.saveCampaign(id,ApiServer.body(x,CampaignUpdate.class));case "suppressions"->engine.saveSuppression(id,ApiServer.body(x,SuppressionUpdate.class));default->throw new Problem(404,"Resource not found");};
        }
        if(p.length==4){
            if(method.equals("GET") && p[3].equals("history"))return engine.history(resource,p[2]);
            if(resource.equals("runs")){
                if(method.equals("GET") && p[3].equals("results"))return engine.results(p[2],offset,limit,q.get("riskId"));
                if(method.equals("GET") && p[3].equals("reservations"))return engine.reservations(p[2]);
                if(method.equals("POST") && p[3].equals("finalize"))return engine.finalizeRun(p[2]);
                if(method.equals("POST") && p[3].equals("cancel"))return engine.cancel(p[2]);
            }
        }
        throw new Problem(404,"Marketing route or method not found");
    }
}

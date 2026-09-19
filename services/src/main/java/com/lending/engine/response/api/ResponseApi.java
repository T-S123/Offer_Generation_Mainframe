/**
 * Operator response/history/replay API; customer offer visibility uses a separate eligibility-filtered
 * route.
 */
package com.lending.engine.response.api;

import com.lending.engine.response.application.ResponseEngine;
import com.lending.engine.domain.Model.Problem;
import com.sun.net.httpserver.HttpExchange;
import java.util.Map;

/**
 * Operator response/history/replay API; customer offer visibility uses a separate eligibility-filtered
 * route.
 */
public final class ResponseApi {
    private final ResponseEngine engine;
    /** Initializes response API with the supplied configuration and dependencies. */
    public ResponseApi(ResponseEngine engine){this.engine=engine;}
    /** Checks whether the supplied result satisfies the eligibility conditions required by this boundary. */
    public Object eligible(String customer,Map<String,String> query,int limit){return engine.list(customer,query.get("after"),limit);}
    /** Dispatches the HTTP method and path to the corresponding application operation. */
    public Object route(HttpExchange x,String[] p,Map<String,String> query,int limit){String method=x.getRequestMethod();
        if(p.length==1&&method.equals("GET"))return engine.list(null,query.get("after"),limit);
        if(p.length==2&&p[1].equals("health")&&method.equals("GET"))return engine.health();
        if(p.length==2&&method.equals("GET"))return engine.get(p[1]);
        if(p.length==3&&p[2].equals("history")&&method.equals("GET")){long after;try{after=Long.parseLong(query.getOrDefault("after","0"));}catch(NumberFormatException e){throw new Problem(422,"after must be a version number");}return engine.history(p[1],after,limit);}
        if(p.length==3&&p[2].equals("replay")&&method.equals("POST"))return engine.replay(p[1]);
        throw new Problem(404,"Decision response route or method not found");
    }
}

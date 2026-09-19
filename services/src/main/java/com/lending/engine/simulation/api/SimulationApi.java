/**
 * Business API exposes versioned form payloads and immutable experiment results; publication is an
 * explicit separate command.
 */
package com.lending.engine.simulation.api;
import com.lending.engine.simulation.application.SimulationEngine;
import com.lending.engine.simulation.domain.Simulation.*;
import com.lending.engine.api.ApiServer;
import com.lending.engine.domain.Model.Problem;
import com.sun.net.httpserver.HttpExchange;
import java.util.*;

/**
 * Business API exposes versioned form payloads and immutable experiment results; publication is an
 * explicit separate command.
 */
public final class SimulationApi {
    private final SimulationEngine engine;
    /** Initializes simulation API with the supplied configuration and dependencies. */
    public SimulationApi(SimulationEngine engine){this.engine=engine;}
    /** Dispatches the HTTP method and path to the corresponding application operation. */
    public Object route(HttpExchange x,String[] p,Map<String,String> q)throws Exception{String method=x.getRequestMethod();int offset=Integer.parseInt(q.getOrDefault("offset","0")),limit=Integer.parseInt(q.getOrDefault("limit","20"));if(p.length<2)throw new Problem(404,"Business route not found");
        if(p.length==2&&method.equals("GET"))return switch(p[1]){case "catalog"->engine.catalog();case "populations"->engine.populations();case "drafts"->engine.store.list("DRAFT",Draft.class);case "runs"->engine.store.list("RUN",Run.class);case "publications"->engine.store.list("PUBLICATION",Publication.class);default->throw new Problem(404,"Business resource not found");};
        if(p.length==2&&method.equals("POST"))return switch(p[1]){case "populations"->engine.generate(ApiServer.body(x,Generate.class));case "drafts"->engine.create(ApiServer.body(x,CreateDraft.class));case "runs"->engine.submit(ApiServer.body(x,RunRequest.class));default->throw new Problem(404,"Business command not found");};
        if(p.length==3&&p[1].equals("drafts")){if(method.equals("GET"))return engine.store.get("DRAFT",p[2],Integer.parseInt(q.getOrDefault("version","0")),Draft.class);if(method.equals("PUT"))return engine.edit(p[2],ApiServer.body(x,EditDraft.class));}
        if(p.length==3&&p[1].equals("runs")&&method.equals("GET"))return engine.store.get("RUN",p[2],0,Run.class);
        if(p.length==4&&p[1].equals("populations")&&p[3].equals("customers")&&method.equals("GET"))return engine.people(p[2],offset,limit);
        if(p.length==4&&p[1].equals("runs")){if(p[3].equals("rows")&&method.equals("GET")){engine.store.get("RUN",p[2],0,Run.class);return engine.store.rows(p[2],offset,limit);}if(p[3].equals("publish")&&method.equals("POST"))return engine.publish(p[2],ApiServer.body(x,Review.class));}
        throw new Problem(404,"Business route or method not found");
    }
}

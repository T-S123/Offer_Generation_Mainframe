/**
 * Public Step 3 API used by terminal, batch clients and real-time callers; customer financial inputs
 * always come from Step 2.
 */
package com.lending.engine.bureau.api;

import com.lending.engine.api.ApiServer;
import com.lending.engine.application.CustomerEngine;
import com.lending.engine.bureau.application.BureauEngine;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.bureau.infrastructure.*;
import com.lending.engine.domain.Model.Problem;
import com.sun.net.httpserver.HttpExchange;
import java.util.*;

/**
 * Public Step 3 API used by terminal, batch clients and real-time callers; customer financial inputs
 * always come from Step 2.
 */
public final class BureauApi {
    private final BureauEngine engine;private final CustomerEngine customers;private final KafkaTransport kafka;
    /** Initializes bureau API with the supplied configuration and dependencies. */
    public BureauApi(BureauEngine engine,CustomerEngine customers,KafkaTransport kafka){this.engine=engine;this.customers=customers;this.kafka=kafka;}
    /** Dispatches the HTTP method and path to the corresponding application operation. */
    public Object route(HttpExchange x,String[] p,Map<String,String> q,int limit)throws Exception {String method=x.getRequestMethod();
        if(p.length==2&&p[1].equals("health")&&method.equals("GET")){var health=new LinkedHashMap<>(engine.queue.health());health.put("kafka",kafka==null?"DISABLED":kafka.status());return health;}
        if(p.length==2&&p[1].equals("requests")&&method.equals("POST"))return engine.submit(ApiServer.body(x,Submit.class));
        if(p.length==2&&p[1].equals("batches")&&method.equals("POST"))return engine.batch(ApiServer.body(x,BatchRequest.class));
        if(p.length==2&&Set.of("requests","batches").contains(p[1])&&method.equals("GET"))return engine.queue.list(p[1].equals("batches"),q.get("after"),limit);
        if(p.length==3&&p[1].equals("requests")&&method.equals("GET"))return engine.queue.get(p[2]);
        if(p.length==3&&p[1].equals("batches")&&method.equals("GET"))return engine.queue.batchStatus(p[2]);
        if(p.length==4&&p[1].equals("batches")&&p[3].equals("items")&&method.equals("GET"))return engine.queue.batchItems(p[2],q.get("after"),limit);
        if(p.length==3&&p[1].equals("sources")&&method.equals("GET"))return engine.source.page(p[2],number(q.getOrDefault("after","-1")),limit);
        if(p.length==3&&p[1].equals("profiles")){customers.get(p[2]);
            if(method.equals("POST"))return engine.credit.profile(p[2]);
            if(method.equals("PUT"))return engine.credit.change(p[2],ApiServer.body(x,ProfileChange.class));
        }
        if(p.length==4&&p[1].equals("profiles")&&p[3].equals("history")&&method.equals("GET")){customers.get(p[2]);return engine.credit.history(p[2],number(q.getOrDefault("after","0")));}
        throw new Problem(404,"Bureau route or method not found");
    }
    /** Parses a numeric API parameter and rejects invalid integer text. */
    private static int number(String value){try{return Integer.parseInt(value);}catch(NumberFormatException e){throw new Problem(422,"after must be an integer");}}
}

/**
 * Offer-scoped underwriting preserves source history and freshness; supplied declines/incompleteness
 * remain binding and approvals must also meet the offer's rules.
 */
package com.lending.engine.simulation.application;
import com.lending.engine.domain.Model.*;
import com.lending.engine.marketing.domain.Marketing.Offer;
import com.lending.engine.simulation.infrastructure.CobolBusinessPolicy;
import com.lending.engine.simulation.domain.BusinessRules.*;
import java.util.*;

/**
 * Offer-scoped underwriting preserves source history and freshness; supplied declines/incompleteness
 * remain binding and approvals must also meet the offer's rules.
 */
public final class PublishedPolicies {
    private final CobolBusinessPolicy policy;
    /** Carries key data for published policies operations. */
    private record Key(CustomerInput customer,Product product,Stage rules,String stage,boolean suppressed) {}
    private final Map<Key,Result> cache=Collections.synchronizedMap(new LinkedHashMap<>(128,.75f,true){
        /** Evicts the oldest cache entry when the configured size limit is exceeded. */
        protected boolean removeEldestEntry(Map.Entry<Key,Result> e){return size()>4096;}});
    /** Invokes the shared COBOL evaluator for the published rule request. */
    private Result evaluate(Customer c,Offer o,Stage rules,String stage,boolean suppressed){var key=new Key(c.current().data(),o.data().product(),rules,stage,suppressed);var prior=cache.get(key);if(prior!=null)return prior;var result=policy.customer(key.customer(),key.product(),rules,stage,suppressed);cache.put(key,result);return result;}
    /** Initializes published policies with the supplied configuration and dependencies. */
    public PublishedPolicies(CobolBusinessPolicy policy){this.policy=policy;}
    /**
     * Evaluates underwriting against the published offer rules while preserving binding supplied
     * decisions.
     */
    public Result underwriting(Customer c,Offer o){var d=c.latest(o.data().product());if(d==null)return new Result("INCOMPLETE",List.of("NO_UNDERWRITING_SOURCE"));if(o.rules()==null||d.source().equals("IMPORTED")&&d.status()!=Status.ELIGIBLE)return new Result(d.status().name(),d.reasons());return evaluate(c,o,o.rules().underwriting(),"U",false);}
    /** Evaluates the published marketing criteria after mandatory consent and suppression gates. */
    public Result marketing(Customer c,Offer o,boolean suppressed){return o.rules()==null?new Result("ELIGIBLE",List.of()):evaluate(c,o,o.rules().marketing(),"M",suppressed);}
    /** Builds the version identifier for a published offer rule stage. */
    public static String version(Offer o){return o.rules().id()+"-v"+o.rules().version();}
}

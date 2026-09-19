/**
 * Customer read model with local eligibility gates and bounded warehouse audit queries; historical copies
 * never grant customer visibility.
 */
package com.lending.engine.storage;
import com.lending.engine.offers.OfferSource;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.infrastructure.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/**
 * Customer read model with local eligibility gates and bounded warehouse audit queries; historical copies
 * never grant customer visibility.
 */
public final class StorageViews implements AutoCloseable {
    public final OfferCopyLoader customer,warehouse;private final OfferSource authority;
    /** Initializes storage views with the supplied configuration and dependencies. */
    public StorageViews(OfferCopyLoader customer,OfferCopyLoader warehouse,OfferSource authority){this.customer=customer;this.warehouse=warehouse;this.authority=authority;}
    /** Starts the customer and enterprise offer-copy consumers. */
    public void start(){customer.start();warehouse.start();}
    /** Retrieves a customer-owned offer only after current eligibility checks pass. */
    public JsonNode customerOffer(String customerId,String id){var stored=customer.store.get(id).path("offer");if(!stored.path("customerId").asText().equals(customerId))throw new Problem(404,"Customer offer not found");var result=visible(stored);if(result==null)throw new Problem(409,"Offer no longer available");return result;}
    /** Returns currently visible copied offers owned by the requested customer. */
    public Map<String,Object> customer(String customerId,String after,int limit){var page=customer.store.page(customerId,after,limit);var rows=new ArrayList<JsonNode>();for(var event:Json.MAPPER.valueToTree(page).path("items")){var stored=event.path("offer");if(!stored.path("preScreen").path("visible").asBoolean()&&!stored.path("marketing").path("visible").asBoolean())continue;try{var visible=visible(stored);if(visible!=null)rows.add(visible);}catch(Problem e){if(e.status!=404&&e.status!=409)throw e;}}
        var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("nextAfter",page.get("nextAfter"));result.put("status","CURRENT_ELIGIBILITY_CHECKED");result.put("copySemantics","EVENTUAL_SELECTION_AND_OFFER_COPY");return result;}
    /** Checks current eligibility before exposing a copied offer to the customer. */
    private ObjectNode visible(JsonNode stored){var context=authority.context(stored.path("id").asText());if(context.response().version()!=stored.path("qualification").path("responseVersion").asLong()||!context.response().customerId().equals(stored.path("customerId").asText()))return null;
        var result=(ObjectNode)stored.deepCopy();var original=stored.path("preScreen");if(!usable(original)||!context.original().equals(Json.MAPPER.convertValue(original.path("terms"),OfferSource.Terms.class)))result.putNull("preScreen");
        var personal=stored.path("marketing");if(!usable(personal))result.putNull("marketing");else try{var approval=authority.check(personal.path("variantQualificationId").asText());if(!approval.responseId().equals(stored.path("id").asText())||!approval.terms().equals(Json.MAPPER.convertValue(personal.path("terms"),OfferSource.Terms.class)))result.putNull("marketing");}catch(Problem e){if(e.status!=404&&e.status!=409)throw e;result.putNull("marketing");}
        if(result.path("preScreen").isNull()&&result.path("marketing").isNull())return null;if(result.path("marketing").isNull())result.putObject("deltas");var choice=result.path("selection");if(!choice.isNull()){var target=result.path(choice.path("kind").asText().equals("ORIGINAL")?"preScreen":"marketing");if(!choice.path("available").asBoolean()||choice.path("sourceVersion").asLong()!=context.response().version()||target.isNull()||!target.path("terms").equals(choice.path("terms")))result.putNull("selection");}return result;
    }
    /** Checks whether a stored choice is usable under its current qualification and validity period. */
    private static boolean usable(JsonNode item){if(item.isNull()||!item.path("visible").asBoolean())return false;try{return Instant.parse(item.path("validUntil").asText()).isAfter(Instant.now());}catch(Exception e){return false;}}
    /** Reports component progress and availability for operational health checks. */
    public Map<String,Object> health(){return Map.of("customer",customer.health(),"warehouse",warehouse.health());}
    /** Dispatches the HTTP method and path to the corresponding application operation. */
    public Object route(String[] p,Map<String,String> q){try{if(p[0].equals("offer-copies")&&p.length==2&&p[1].equals("health"))return health();if(p[0].equals("warehouse")&&p.length>=2&&p[1].equals("offers")){if(p.length==2)return warehouse.store.page(null,q.get("after"),Integer.parseInt(q.getOrDefault("limit","20")));if(p.length==3)return warehouse.store.get(p[2]);if(p.length==4&&p[3].equals("history"))return warehouse.store.history(p[2],Long.parseLong(q.getOrDefault("after","0")));}throw new Problem(404,"Copy route not found");}catch(NumberFormatException e){throw new Problem(422,"Invalid numeric pagination parameter");}}
    /** Releases the resources owned by this component. */
    public void close(){customer.close();warehouse.close();}
}

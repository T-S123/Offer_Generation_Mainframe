package com.lending.offers.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.lending.offers.Json;
import com.lending.offers.infrastructure.OfferStore;
import static com.lending.offers.infrastructure.Database.*;
import static com.lending.offers.domain.Model.*;
import java.time.Instant;
import java.sql.Connection;
import java.util.*;

/** Owner-managed session quotes stay separate from offer families until explicit, atomic customer selection. */
public final class SessionOffers {
    private final OfferStore store;
    private final OfferEngine engine;
    public SessionOffers(OfferStore store,OfferEngine engine){this.store=store;this.engine=engine;}
    private static void identifier(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{16,80}"))throw new Json.Fault(422,"Session identifier required");}
    public Object open(String customer,JsonNode body){String id=body.path("sessionId").asText();identifier(id);Instant until;
        try{until=Instant.parse(body.path("expiresAt").asText());}catch(Exception e){throw new Json.Fault(422,"Session expiry required");}
        if(!until.isAfter(Instant.now())||until.isAfter(Instant.now().plusSeconds(28860)))throw new Json.Fault(422,"Invalid session lifetime");
        return store.db.tx(c->{execute(c,"INSERT INTO personalization_sessions(id,customer_id,expires_at) VALUES(?,?,?::timestamptz) ON CONFLICT DO NOTHING",id,customer,until.toString());active(c,customer,id);return Map.of("active",true);});
    }
    public Object status(String customer,String session){return store.db.tx(c->{active(c,customer,session);return Map.of("active",true);});}
    public Object close(String customer,String session){identifier(session);return store.db.tx(c->{execute(c,"UPDATE personalization_sessions SET closed=true WHERE id=? AND customer_id=?",session,customer);return Map.of("closed",true);});}
    /** Locks the session before quotes, serializing issuance/selection against logout and expiry. */
    private static void active(Connection c,String customer,String session)throws Exception {identifier(session);String found=scalar(c,"SELECT id FROM personalization_sessions WHERE id=? AND customer_id=? AND NOT closed AND expires_at>now() FOR UPDATE",session,customer);if(found==null)throw new Json.Fault(409,"Personalization session expired");}
    public Object issue(String customer,String session,JsonNode request){
        var offer=engine.preparePersonalization(customer,request);String id="PQ-"+Json.hash(List.of(customer,session,request)).substring(0,60);
        return store.db.tx(c->{active(c,customer,session);execute(c,"INSERT INTO session_offers(id,session_id,customer_id,profile_version,approval_id,payload) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",id,session,customer,request.path("profileVersion").asInt(),request.path("approvalId").asText(),Json.write(offer));return quote(c,customer,session,id);});
    }
    private static JsonNode quote(Connection c,String customer,String session,String id)throws Exception {
        try(var p=statement(c,"SELECT payload,profile_version,approval_id,selection FROM session_offers WHERE id=? AND customer_id=? AND (session_id=? OR selection IS NOT NULL) FOR UPDATE",id,customer,session);var r=p.executeQuery()){
            if(!r.next())throw new Json.Fault(404,"Personalized offer not found");var n=Json.MAPPER.createObjectNode();n.put("id",id).put("profileVersion",r.getInt(2)).put("approvalId",r.getString(3));n.set("offer",Json.tree(r.getString(1)));n.put("status",r.getString(4)==null?"AVAILABLE":"SELECTED");if(r.getString(4)!=null)n.set("selection",Json.tree(r.getString(4)));return n;
        }
    }
    /** Rechecks normalized monetary terms, independent of JSON number representation; selected records survive later sessions. */
    public JsonNode review(String customer,String session,String id){var q=store.db.tx(c->{active(c,customer,session);return quote(c,customer,session,id);});if(!q.has("selection")){var current=engine.preparePersonalization(customer,Json.MAPPER.valueToTree(Map.of("approvalId",q.path("approvalId").asText(),"profileVersion",q.path("profileVersion").asInt())));if(!current.personalized().terms().equals(terms(q.at("/offer/personalized/terms"))))throw new Json.Fault(409,"Personalized terms changed");}return q;}
    public Object list(String customer,String session){var ids=store.db.tx(c->{active(c,customer,session);var rows=new ArrayList<String>();try(var p=statement(c,"SELECT id FROM session_offers WHERE customer_id=? AND (session_id=? OR selection IS NOT NULL) ORDER BY id",customer,session);var r=p.executeQuery()){while(r.next())rows.add(r.getString(1));}return rows;});var rows=new ArrayList<JsonNode>();for(String id:ids)try{rows.add(review(customer,session,id));}catch(Json.Fault stale){if(stale.status!=404&&stale.status!=409)throw stale;}return Map.of("items",rows);}
    /** Installs only the reviewed quote and records selection/storage events in the same owner transaction. */
    public Object select(String customer,String session,String id,PresentedSelection request){
        var q=review(customer,session,id);var offer=Json.MAPPER.convertValue(q.path("offer"),OfferSet.class);
        if(request==null||!"PERSONALIZED".equals(request.kind())||request.terms()==null||!request.terms().equals(offer.personalized().terms())||request.sourceVersion()!=offer.sourceVersion()||request.requestId()==null||!request.requestId().matches("[A-Za-z0-9_.:-]{1,80}"))throw new Json.Fault(409,"Review these exact personalized terms before selecting");
        var expected=q.has("selection")?null:engine.current(offer.id());
        return store.db.tx(c->{active(c,customer,session);var locked=quote(c,customer,session,id);if(locked.has("selection"))return locked.path("selection");
            var result=store.installSelected(c,expected,offer,new Selection(request.requestId(),request.sourceVersion(),"PERSONALIZED"),request.terms());execute(c,"UPDATE session_offers SET selection=? WHERE id=?",Json.write(result),id);return result;});
    }
}

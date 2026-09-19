/** Catalog integrity and reservation time semantics; qualification and quota comparisons execute in COBOL. */
package com.lending.engine.marketing.domain;

import static com.lending.engine.domain.CustomerRules.*;
import static com.lending.engine.marketing.domain.Marketing.*;
import com.lending.engine.domain.Model.Problem;
import java.time.*;
import java.util.*;

/** Catalog integrity and reservation time semantics; qualification and quota comparisons execute in COBOL. */
public final class MarketingRules {
    /** Prevents instantiation of this utility-only type. */
    private MarketingRules() {}
    /** Checks that an identifier uses the supported characters and length. */
    public static void identifier(String id) { require(id!=null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,59}"),"Use a 1-60 character ID: letters, digits, underscore or hyphen"); }
    /** Validates catalog offer identifiers, terms, dates and supported product settings. */
    public static void offer(OfferInput o) {
        require(o!=null,"offer is required");text(o.name(),"name",60,true);
        require(o.product()!=null && o.active()!=null,"product and active are required");dates(o.startsOn(),o.endsOn());
        require(o.minimumAmountUsd()!=null && o.maximumAmountUsd()!=null,"Both amount limits are required");
        money(o.minimumAmountUsd(),"minimumAmountUsd");money(o.maximumAmountUsd(),"maximumAmountUsd");
        require(o.minimumAmountUsd().signum()>0 && o.minimumAmountUsd().compareTo(o.maximumAmountUsd())<=0,"Amount range must be positive and ordered");
        require(o.illustrativeAprPct()!=null && o.annualFeeUsd()!=null && o.termMonths()!=null,"Illustrative APR, fee and term are required");
        money(o.illustrativeAprPct(),"illustrativeAprPct");money(o.annualFeeUsd(),"annualFeeUsd");
        require(o.illustrativeAprPct().compareTo(new java.math.BigDecimal("100"))<=0,"APR must be at most 100");
        integer(o.termMonths(),"termMonths",0,120);
        require(o.product()==com.lending.engine.domain.Model.Product.CREDIT_CARD ? o.termMonths()==0 : o.termMonths()>0,"Cards use term 0; loans require a positive term");
    }
    /** Validates campaign limits, offer references and the active date interval. */
    public static void campaign(CampaignInput c) {
        require(c!=null,"campaign is required");text(c.name(),"name",60,true);dates(c.startsOn(),c.endsOn());
        require(c.active()!=null && c.priority()!=null && c.capacity()!=null && c.minimumTenureMonths()!=null && c.excludeExistingProduct()!=null,"All campaign controls are required");
        integer(c.priority(),"priority",1,999);integer(c.capacity(),"capacity",1,50000);integer(c.minimumTenureMonths(),"minimumTenureMonths",0,1200);
        require(c.offerIds()!=null && !c.offerIds().isEmpty() && c.offerIds().size()<=10,"Select 1-10 offers");
        c.offerIds().forEach(MarketingRules::identifier);require(new HashSet<>(c.offerIds()).size()==c.offerIds().size(),"Duplicate offers");
    }
    /** Validates the configurable demonstration throttling policy. */
    public static void policy(PolicyInput p) {
        require(p!=null && p.cooldownDays()!=null && p.rollingWindowDays()!=null && p.maximumReservations()!=null && p.reservationDays()!=null,"All policy controls are required");
        integer(p.cooldownDays(),"cooldownDays",0,365);integer(p.rollingWindowDays(),"rollingWindowDays",1,365);
        integer(p.maximumReservations(),"maximumReservations",1,50000);integer(p.reservationDays(),"reservationDays",1,30);
    }
    /** Validates a customer suppression entry and its effective dates. */
    public static void suppression(SuppressionInput s) {
        require(s!=null && s.active()!=null,"suppression and active are required");text(s.reason(),"reason",60,true);
        if(s.expiresOn()!=null) date(s.expiresOn());
    }
    /** Checks the requested optimistic version against the current stored revision. */
    public static void version(Integer expected,int actual) { require(expected!=null && expected>=0,"expectedVersion must be provided (0 creates)");if(expected!=actual)throw new Problem(409,"Record changed; reload its current version"); }
    /** Parses and validates a date used by business configuration. */
    public static LocalDate date(String value) {
        try { require(value!=null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),"Use YYYY-MM-DD dates");return LocalDate.parse(value); }
        catch(DateTimeException e){throw new Problem(422,"Invalid calendar date");}
    }
    /** Checks that the configured validity dates form an ordered interval. */
    public static void dates(String start,String end) { require(!date(end).isBefore(date(start)),"End date precedes start date"); }
    /** Checks whether the supplied date falls within the configured validity interval. */
    public static boolean inDate(String start,String end,LocalDate today) { return !today.isBefore(date(start)) && !today.isAfter(date(end)); }
    /** Checks whether a suppression applies to the customer at the evaluation time. */
    public static boolean suppressed(Suppression s,LocalDate today) { return s!=null && s.data().active() && (s.data().expiresOn()==null || !today.isAfter(date(s.data().expiresOn()))); }
    /** Checks whether a reservation is active at the supplied time. */
    public static boolean active(Reservation r,Instant now) { return r.releasedAt()==null && Instant.parse(r.expiresAt()).isAfter(now); }
}

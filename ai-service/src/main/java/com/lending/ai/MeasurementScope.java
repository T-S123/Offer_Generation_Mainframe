package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.lending.ai.Json.*;

/** Versioned measurement scope uses inclusive local dates, exclusive UTC end and a fixed evidence cutoff. */
public record MeasurementScope(String startDate,String endDate,String timezone,String dateBasis,String sourceMode,
                               int offerVersion,String variant,String evidenceCutoff) {
    public static final String VERSION="performance-v2";
    public static MeasurementScope parse(JsonNode input,int publicationVersion,Instant now){
        require(input!=null&&input.isObject(),"MEASUREMENT_SCOPE","A structured measurement scope is required");
        for(String name:List.of("startDate","endDate","timezone","dateBasis","sourceMode","variant","evidenceCutoff"))require(!input.hasNonNull(name)||input.path(name).isTextual(),"MEASUREMENT_SCOPE",name+" must be text");
        require(!input.hasNonNull("offerVersion")||input.path("offerVersion").isIntegralNumber(),"OFFER_VERSION","offerVersion must be an integer");
        fields(input,"startDate","endDate","timezone","dateBasis","sourceMode","offerVersion","variant","evidenceCutoff");
        String start=nullableText(input,"startDate"),end=nullableText(input,"endDate");
        require((start==null)==(end==null),"DATE_RANGE","Supply both startDate and endDate, or neither");
        String zone=value(input,"timezone","America/Los_Angeles"),basis=value(input,"dateBasis","FIRST_ASSESSED"),mode=value(input,"sourceMode","DEMO"),variant=value(input,"variant","OFFER_FAMILY");
        try{ZoneId.of(zone);if(start!=null){LocalDate a=LocalDate.parse(start),b=LocalDate.parse(end);require(!b.isBefore(a)&&ChronoUnit.DAYS.between(a,b)<=3660,"DATE_RANGE","Use an ordered range of at most ten years");}}
        catch(DateTimeException e){throw new Fault(422,"DATE_RANGE","Use valid ISO dates and an IANA timezone");}
        require(Set.of("FIRST_ASSESSED","FIRST_ELIGIBLE").contains(basis),"DATE_BASIS","Use FIRST_ASSESSED or FIRST_ELIGIBLE");
        require(Set.of("DEMO","OBSERVED_REAL").contains(mode),"SOURCE_MODE","Use an explicit outcome source");
        require(Set.of("OFFER_FAMILY","ORIGINAL","PERSONALIZED").contains(variant),"VARIANT","Choose an exact variant or OFFER_FAMILY");
        int version=input.hasNonNull("offerVersion")?input.path("offerVersion").asInt():publicationVersion;
        require(version>0&&version==publicationVersion,"OFFER_VERSION","Use the frozen published offer version");
        String cutoff=value(input,"evidenceCutoff",now.toString());try{require(!Instant.parse(cutoff).isAfter(now),"AS_OF_FUTURE","Evidence cutoff cannot be in the future");}catch(DateTimeException e){throw new Fault(422,"EVIDENCE_CUTOFF","Use an ISO instant for evidenceCutoff");}
        return new MeasurementScope(start,end,zone,basis,mode,version,variant,cutoff);
    }
    public Instant cutoff(){return Instant.parse(evidenceCutoff);}
    public Instant start(){return startDate==null?Instant.MIN:LocalDate.parse(startDate).atStartOfDay(ZoneId.of(timezone)).toInstant();}
    public Instant end(){return endDate==null?cutoff():LocalDate.parse(endDate).plusDays(1).atStartOfDay(ZoneId.of(timezone)).toInstant();}
    public boolean includes(String instant){Instant at=Instant.parse(instant);return !at.isBefore(start())&&(endDate==null?!at.isAfter(cutoff()):at.isBefore(end()))&&!at.isAfter(cutoff());}
    public JsonNode definition(int followUpDays){return obj("version",VERSION,"scope",this,"startInclusive",startDate==null?null:start().toString(),"endExclusive",endDate==null?null:end().toString(),"cohort","One customer per published offer version; first recorded entry on the chosen date basis","eligibility","Eligible assessed customers / all assessed customers","acceptance","Distinct recorded selectors / eligible customers within their recorded follow-up window","aggregation","RATIO_OF_COMBINED_COUNTS","followUpDays",followUpDays,"reconciliationHours",24);}
    static String nullableText(JsonNode n,String field){String value=n.path(field).asText("").trim();return value.isBlank()?null:value;}
    static String value(JsonNode n,String field,String fallback){String s=nullableText(n,field);return s==null?fallback:s;}
}

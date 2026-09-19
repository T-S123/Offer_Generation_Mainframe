/**
 * Versioned minimized storage-event boundary with qualification-bound selections. Rejects unknown/private
 * fields before either downstream database receives a payload.
 */
package com.lending.engine.storage;
import com.fasterxml.jackson.databind.JsonNode;
import com.lending.engine.domain.Model.Problem;
import java.time.Instant;
import java.util.*;

/**
 * Versioned minimized storage-event boundary with qualification-bound selections. Rejects unknown/private
 * fields before either downstream database receives a payload.
 */
public final class StorageContract {
    /** Prevents instantiation of this utility-only type. */
    private StorageContract(){}
    /** Rejects payload fields outside the permitted storage-event contract. */
    private static void fields(JsonNode n,String... names){if(n==null||!n.isObject())fail();var allowed=Set.of(names);var actual=new HashSet<String>();n.fieldNames().forEachRemaining(actual::add);if(!actual.equals(allowed))fail();}
    /** Validates a required identifier in the minimized storage-event contract. */
    private static void id(JsonNode n,boolean nullable){if(nullable&&n.isNull())return;if(!n.isTextual()||!n.asText().matches("[A-Za-z0-9_.:-]{1,160}"))fail();}
    /** Validates a count field in the storage-event payload. */
    private static void count(JsonNode n,long minimum){if(!n.isIntegralNumber()||!n.canConvertToLong()||n.asLong()<minimum)fail();}
    /** Validates a timestamp field in the storage-event payload. */
    private static void timestamp(JsonNode n,boolean nullable){if(nullable&&n.isNull())return;try{Instant.parse(n.asText());}catch(Exception e){fail();}}
    /** Validates a numeric field in the storage-event payload. */
    private static void numeric(JsonNode n){if(!n.isNumber()||!Double.isFinite(n.asDouble()))fail();}
    /** Checks that stored offer terms contain only supported financial fields and values. */
    private static void terms(JsonNode n){fields(n,"amountUsd","aprPct","annualFeeUsd","termMonths");for(String f:List.of("amountUsd","aprPct","annualFeeUsd")){numeric(n.path(f));if(n.path(f).asDouble()<0)fail();}count(n.path("termMonths"),0);}
    /** Validates the numeric comparison metrics allowed in downstream storage. */
    private static void metrics(JsonNode n){if(n.isNull())return;fields(n,"fitScore","simulatedAcceptance","monthlyPaymentUsd","totalCostUsd","affordability","amountMatch","costFit","termFit");n.forEach(StorageContract::numeric);}
    /** Validates an offer-storage snapshot and its nested qualification and financial details. */
    private static void item(JsonNode n,String category){if(n.isNull())return;fields(n,"category","status","visible","terms","metrics","variantQualificationId","validUntil");if(!n.path("category").asText().equals(category)||!n.path("visible").isBoolean())fail();id(n.path("status"),false);if(!n.path("terms").isNull())terms(n.path("terms"));if(n.path("visible").asBoolean()&&(n.path("terms").isNull()||!n.path("status").asText().equals("ACTIVE")))fail();metrics(n.path("metrics"));id(n.path("variantQualificationId"),true);timestamp(n.path("validUntil"),true);}
    /** Validates the numeric comparison fields carried by a storage event. */
    private static void numbers(JsonNode n,Set<String> allowed){if(!n.isObject())fail();n.fields().forEachRemaining(e->{if(!allowed.contains(e.getKey()))fail();numeric(e.getValue());});}
    /** Validates the complete event envelope and rejects private or unknown payload fields. */
    public static void validate(JsonNode event){fields(event,"eventId","schemaVersion","type","occurredAt","sequence","offer");id(event.path("eventId"),false);count(event.path("sequence"),1);if(!event.path("schemaVersion").isIntegralNumber()||event.path("schemaVersion").asInt()!=1||!event.path("type").asText().equals("OFFER_STORAGE_UPDATED"))fail();timestamp(event.path("occurredAt"),false);var s=event.path("offer");fields(s,"id","version","customerId","product","currency","qualification","preScreen","marketing","selection","modelId","policyVersion","cohort","modelMetrics","deltas");id(s.path("id"),false);id(s.path("customerId"),false);count(s.path("version"),1);if(!Set.of("PERSONAL_LOAN","CREDIT_CARD","AUTO_LOAN").contains(s.path("product").asText())||!s.path("currency").asText().equals("USD"))fail();id(s.path("modelId"),true);id(s.path("policyVersion"),true);if(!s.path("cohort").isNull())count(s.path("cohort"),0);
        var q=s.path("qualification");fields(q,"runId","riskId","qualificationId","responseVersion","campaignId","campaignVersion","catalogOfferId","catalogOfferVersion","bureauDecisionId","underwritingDecisionId","eligibilityPolicyVersion");for(String key:List.of("runId","riskId","qualificationId","campaignId","catalogOfferId","bureauDecisionId","underwritingDecisionId","eligibilityPolicyVersion"))id(q.path(key),true);for(String key:List.of("responseVersion","campaignVersion","catalogOfferVersion"))count(q.path(key),0);
        if(s.path("preScreen").isNull())fail();item(s.path("preScreen"),"PRE_SCREEN");item(s.path("marketing"),"MARKETING");var choice=s.path("selection");if(!choice.isNull()){fields(choice,"requestId","sourceVersion","kind","terms","selectedAt","available");id(choice.path("requestId"),false);count(choice.path("sourceVersion"),1);if(!Set.of("ORIGINAL","PERSONALIZED").contains(choice.path("kind").asText())||!choice.path("available").isBoolean())fail();terms(choice.path("terms"));timestamp(choice.path("selectedAt"),false);}
        numbers(s.path("modelMetrics"),Set.of("trainingCount","validationCount","testCount","validationSilhouette","testSilhouette","minimumTrainingCohortSize","cohortCount","observedAcceptanceLabels"));numbers(s.path("deltas"),Set.of("fitScore","simulatedAcceptancePercentagePoints","monthlyPaymentUsd","totalCostUsd"));
    }
    /** Raises a contract error so invalid events can be quarantined. */
    private static void fail(){throw new Problem(422,"Invalid storage contract or disallowed field");}
}

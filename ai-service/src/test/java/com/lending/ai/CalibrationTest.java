package com.lending.ai;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Artificial fixtures test calibration gates; these test-only records are never operational outcome evidence. */
class CalibrationTest {
    static void dataset(Store s,String mode,boolean repeatedCustomers){
        Instant through=Instant.now();
        for(int publication=0;publication<5;publication++){
            String pub="p"+publication;Instant at=Instant.parse("2025-01-01T00:00:00Z").plus(Duration.ofDays(publication*35L));
            s.create("SOURCE_WATERMARK",hash(List.of(mode,pub)),obj("data",obj("complete",true,"through",through.toString())));
            for(int i=0;i<100;i++){
                String customer=hash((repeatedCustomers?"":publication+":")+i);String key=pub+":"+customer;double probability=i<50?.2:.8;boolean selected=i<50?i<10:i<90;
                s.create("ENROLLMENT",key,obj("catalogOfferId",pub,"customerKey",customer,"sourceMode",mode,"eligibleAt",at.toString(),"followUpEndsAt",at.plus(Duration.ofDays(14)).toString()));
                s.create("FORECAST_FEATURE",key,obj("catalogOfferId",pub,"customerKey",customer,"sourceMode",mode,"data",obj("product","PERSONAL_LOAN","policyContext","choice-policy-v1","baseProbability",probability,"featureAsOf",at.minusSeconds(1).toString())));
                if(selected)s.create("SELECTION",key,obj("catalogOfferId",pub,"customerKey",customer,"sourceMode",mode,"selectedAt",at.plusSeconds(3600).toString()));
            }
        }
    }
    @Test void noDemoLabelsCanBecomeARealForecast(){
        try(var s=new FoundationTest().store()){
            dataset(s,"DEMO",false);var c=new CalibrationService(s,14);
            assertEquals("INSUFFICIENT_REAL_LABELS",c.readiness("PERSONAL_LOAN","choice-policy-v1").path("status").asText());
            assertThrows(Fault.class,()->c.train("analyst",obj("product","PERSONAL_LOAN","policyContext","choice-policy-v1")));
            assertEquals("NO_ACTIVE_CALIBRATION",c.forecast("PERSONAL_LOAN","choice-policy-v1",List.of(.8)).path("status").asText());
        }
    }
    @Test void prospectiveCalibrationRequiresPassingEvaluationAndExactApproval(){
        try(var s=new FoundationTest().store()){
            dataset(s,"OBSERVED_REAL",false);var c=new CalibrationService(s,14);
            var model=c.train("analyst",obj("product","PERSONAL_LOAN","policyContext","choice-policy-v1"));
            assertEquals("EVALUATED_PASS",model.path("status").asText(),write(model));
            assertEquals(300,model.path("trainingLabels").asInt());assertEquals(100,model.at("/test/n").asInt());
            assertThrows(Fault.class,()->c.activate("another-analyst",obj("modelId",model.path("id"),"datasetHash",model.path("datasetHash"),"confirm",true,"note","Reviewed the untouched temporal evaluation")));
            c.activate("analyst",obj("modelId",model.path("id"),"datasetHash",model.path("datasetHash"),"confirm",true,"note","Reviewed the untouched temporal evaluation"));
            var prediction=c.forecast("PERSONAL_LOAN","choice-policy-v1",List.of(.2,.8));
            assertEquals("CALIBRATED_SELECTION",prediction.path("predictionKind").asText());assertEquals(50,prediction.path("estimatePct").asDouble(),1);
            assertEquals("NO_ACTIVE_CALIBRATION",c.forecast("PERSONAL_LOAN","changed-policy",List.of(.8)).path("status").asText());
            s.create("PUBLICATION","prospective",obj("analyst","analyst","preview",obj("configuration",obj("offer",obj("product","PERSONAL_LOAN")))));
            s.create("FORECAST_FEATURE","prospective-feature",obj("catalogOfferId","prospective","customerKey",hash("new-subject"),"sourceMode","OBSERVED_REAL","data",obj("product","PERSONAL_LOAN","policyContext","choice-policy-v1","baseProbability",.8,"featureAsOf",Instant.now().toString())));
            var frozen=c.freezeForecast("prospective","analyst",obj("product","PERSONAL_LOAN","policyContext","choice-policy-v1"));
            assertTrue(frozen.has("eligibleCohortHash"));assertEquals(hash(frozen),hash(c.freezeForecast("prospective","analyst",obj("product","PERSONAL_LOAN","policyContext","choice-policy-v1"))));
            assertThrows(Fault.class,()->c.freezeForecast("prospective","analyst",obj("product","PERSONAL_LOAN","policyContext","changed-policy")));
            s.create("PUBLICATION","late",obj("analyst","analyst","preview",obj("configuration",obj("offer",obj("product","PERSONAL_LOAN")))));
            s.create("ENROLLMENT","late",obj("catalogOfferId","late","sourceMode","OBSERVED_REAL"));
            assertEquals("FORECAST_LEAKAGE",assertThrows(Fault.class,()->c.freezeForecast("late","analyst",obj("product","PERSONAL_LOAN","policyContext","choice-policy-v1"))).code);
        }
    }
    @Test void repeatedCustomersAcrossPublicationsAreRejected(){
        try(var s=new FoundationTest().store()){
            dataset(s,"OBSERVED_REAL",true);var c=new CalibrationService(s,14);
            assertEquals("CUSTOMER_LEAKAGE",assertThrows(Fault.class,()->c.train("analyst",obj("product","PERSONAL_LOAN","policyContext","choice-policy-v1"))).code);
        }
    }
}

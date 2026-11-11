package com.lending.ai;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.lending.ai.Contracts.*;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;
/** Confidence is a model assessment; deterministic guards enforce the six-exchange ceiling. */
class PersonalizationConversationTest {
    Request request(int rounds){return new Request("1.0.0","request","workflow","review",1,Instant.now().plusSeconds(60).toString(),obj("network","PERSONALIZATION","stage","INITIAL_REVIEW","executionContext",obj("conversation",obj("roundsRemaining",6-rounds))));}
    @Test void seventhQuestionIsRepairedInternallyAndNeverExposed(){
        var calls=new AtomicInteger();var runner=new AgentRunner(Network.PERSONALIZATION,Role.ORCHESTRATOR,(i,p,s)->{calls.incrementAndGet();return new ModelClient.Reply(tree(new Decision("NEEDS_INPUT","Another question",null,List.of(),List.of(),List.of(),List.of(),null,new Question("extra","TRADEOFF","Can we change the term?","Changes payment",List.of(),List.of()),null)),"fixture",10,10);},NetworkIsolationTest.unused());
        assertEquals("BLOCKED",runner.run(request(6),()->false).decision().status());assertEquals(3,calls.get());
    }
    @Test void evidenceBasedHighConfidenceCanFinishWithoutQuestions(){
        var stop=assessed(new Decision("NO_IMPROVEMENT","Existing terms reach the permitted envelope",null,List.of(),List.of(),List.of(),List.of(),null),new PlanningAssessment(.95,"No remaining improvement","All eligible options already at approved limits",List.of()),List.of());
        assertDoesNotThrow(()->PersonalizationConversation.validate(stop,request(0)));
        var uncertain=assessed(stop,new PlanningAssessment(.4,"No improvement","Preferences are not known",List.of("Payment preference")),List.of());assertThrows(Fault.class,()->PersonalizationConversation.validate(uncertain,request(0)));
        assertEquals("NO_IMPROVEMENT",decision(tree(stop),Network.PERSONALIZATION).status());assertThrows(Fault.class,()->decision(tree(stop),Network.SIMULATION));
    }
}

/** Executes actual COBOL credit rules, boundary cases and concurrent persistent-stream requests. */
package com.lending.engine;

import com.lending.engine.credit.CobolCreditPolicy;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.domain.Model.Product;
import com.lending.engine.infrastructure.Json;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Executes actual COBOL credit rules, boundary cases and concurrent persistent-stream requests. */
class CreditPolicyTest {
    static final Instant NOW=Instant.parse("2026-09-16T12:00:00Z");
    /** Builds the credit request used to exercise the compiled COBOL policy. */
    static CreditInput input(Product product){return new CreditInput("request","subject","customer",product,new BigDecimal("10000"),new BigDecimal("8000"),"SELF_REPORTED",new BigDecimal("15000"),product==Product.CREDIT_CARD?0:48,new BigDecimal("10"));}
    /** Builds independent bureau facts for a controlled credit scenario. */
    static Facts facts(){return new Facts(FileStatus.MATCHED,740,new BigDecimal("400"),20,0,1,60,false,NOW.toString());}
    /** Loads or constructs the independent bureau profile used by the test. */
    static BureauProfile profile(Facts f){return new BureauProfile("subject","customer",1,"LOCAL_IMPORT",NOW.toString(),f);}
    /** Builds a modified fixture for exercising version and eligibility changes. */
    static <T>T change(T value,String field,Object replacement,Class<T> type){var node=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(value);node.set(field,Json.MAPPER.valueToTree(replacement));return Json.read(node.toString(),type);}
    /** Verifies that all products use the real policy and independent bureau score. */
    @Test void allProductsUseTheRealPolicyAndIndependentBureauScore(){try(var policy=new CobolCreditPolicy(Path.of("../build/licbbatch"),2,Clock.fixed(NOW,ZoneOffset.UTC))){for(var product:Product.values()){var decision=policy.evaluate(input(product),profile(facts()));assertEquals("APPROVED",decision.outcome());assertEquals("CBR-2026-001",decision.policyVersion());assertEquals(76,CobolCreditPolicy.encode(input(product),profile(facts()),NOW).length());}
        assertTrue(policy.evaluate(input(Product.PERSONAL_LOAN),profile(change(facts(),"creditScore",679,Facts.class))).reasons().contains("SCORE_BELOW_MINIMUM"));
        assertEquals("APPROVED",policy.evaluate(input(Product.PERSONAL_LOAN),profile(change(facts(),"creditScore",680,Facts.class))).outcome());}}
    /** Verifies that unknown frozen stale and thin files refer for review. */
    @Test void unknownFrozenStaleAndThinFilesReferForReview(){try(var policy=new CobolCreditPolicy(Path.of("../build/licbbatch"),1,Clock.fixed(NOW,ZoneOffset.UTC))){for(Facts f:List.of(change(facts(),"creditScore",null,Facts.class),change(facts(),"fileStatus",FileStatus.FROZEN,Facts.class),change(facts(),"reportedAt",NOW.minus(Duration.ofDays(31)).toString(),Facts.class),change(facts(),"oldestAccountMonths",5,Facts.class)))assertEquals("REVIEW",policy.evaluate(input(Product.PERSONAL_LOAN),profile(f)).outcome());
        assertEquals("APPROVED",policy.evaluate(input(Product.PERSONAL_LOAN),profile(change(facts(),"reportedAt",NOW.minus(Duration.ofDays(30)).toString(),Facts.class))).outcome());}}
    /** Verifies that debt includes proposed payment and zero APR is supported. */
    @Test void debtIncludesProposedPaymentAndZeroAprIsSupported(){try(var policy=new CobolCreditPolicy(Path.of("../build/licbbatch"),1,Clock.fixed(NOW,ZoneOffset.UTC))){var loan=change(input(Product.PERSONAL_LOAN),"monthlyIncomeUsd",new BigDecimal("2000"),CreditInput.class);loan=change(loan,"aprPct",BigDecimal.ZERO,CreditInput.class);loan=change(loan,"amountUsd",new BigDecimal("24000"),CreditInput.class);
        assertEquals("APPROVED",policy.evaluate(loan,profile(facts())).outcome());assertTrue(policy.evaluate(loan,profile(change(facts(),"monthlyDebtUsd",new BigDecimal("400.01"),Facts.class))).reasons().contains("DEBT_TO_INCOME_EXCEEDED"));
        assertTrue(policy.evaluate(input(Product.AUTO_LOAN),profile(change(facts(),"bankruptcy",true,Facts.class))).reasons().contains("BANKRUPTCY_REPORTED"));}}
    /** Verifies that persistent workers do not cross wire concurrent responses. */
    @Test void persistentWorkersDoNotCrossWireConcurrentResponses()throws Exception{try(var policy=new CobolCreditPolicy(Path.of("../build/licbbatch"),4,Clock.fixed(NOW,ZoneOffset.UTC))){var executor=Executors.newFixedThreadPool(8);try{var work=new ArrayList<Callable<Boolean>>();for(int i=0;i<500;i++){boolean approve=i%2==0;work.add(()->policy.evaluate(input(Product.CREDIT_CARD),profile(change(facts(),"bankruptcy",!approve,Facts.class))).outcome().equals(approve?"APPROVED":"DECLINED"));}for(var result:executor.invokeAll(work))assertTrue(result.get());}finally{executor.shutdownNow();}}}
}

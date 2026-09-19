/** Model partition/reproducibility and economic comparison tests independent of transport. */
package com.lending.offers;
import com.lending.offers.Json;
import com.lending.offers.domain.*;
import com.lending.offers.application.*;
import com.lending.offers.infrastructure.*;
import com.lending.offers.api.*;
import static com.lending.offers.domain.Model.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

/** Model partition/reproducibility and economic comparison tests independent of transport. */
class PersonalizationTest {
    /** Creates the synthetic population used by this test. */
    static List<JsonNode> population(int count){var random=new Random(42);var rows=new ArrayList<JsonNode>();for(int i=0;i<count;i++)rows.add(Json.MAPPER.valueToTree(Map.of("customerId","customer-"+i,"origin","SYNTHETIC","monthlyIncomeUsd",2000+random.nextInt(12000),"monthlyDebtPaymentsUsd",random.nextInt(2000),"creditUtilizationPct",random.nextInt(101),"bankingTenureMonths",random.nextInt(120),"depositBalanceUsd",random.nextInt(20000),"monthlySpendUsd",random.nextInt(4000))));return rows;}
    /** Builds the exact financial terms used in an offer comparison. */
    static Terms terms(double amount,double apr,double fee,int months){return new Terms(BigDecimal.valueOf(amount),BigDecimal.valueOf(apr),BigDecimal.valueOf(fee),months);}
    /** Verifies that fitted model is deterministic and partitions are disjoint. */
    @Test void fittedModelIsDeterministicAndPartitionsAreDisjoint(){var rows=population(300);var a=Cohorts.train("a",new Training("batch",17),rows);var b=Cohorts.train("b",new Training("batch",17),rows);assertEquals(a.metrics(),b.metrics());assertEquals(a.datasetHash(),b.datasetHash());assertEquals(Json.write(a.centroids()),Json.write(b.centroids()));assertEquals(180,a.metrics().get("trainingCount"));assertEquals(60,a.metrics().get("validationCount"));assertEquals(60,a.metrics().get("testCount"));assertTrue(a.metrics().get("testSilhouette")>=-1&&a.metrics().get("testSilhouette")<=1);assertTrue(a.metrics().get("minimumTrainingCohortSize")>=5);assertEquals(0,a.metrics().get("observedAcceptanceLabels"));assertFalse(a.features().contains("customerId"));}
    /** Verifies that missing features stay finite and small batches are rejected. */
    @Test void missingFeaturesStayFiniteAndSmallBatchesAreRejected(){var f=Json.tree("{}");for(double x:Cohorts.vector(f))assertTrue(Double.isFinite(x));assertThrows(Json.Fault.class,()->Cohorts.train("a",new Training("batch",17),population(59)));}
    /** Verifies that comparison shows real tradeoffs and no personalization bonus. */
    @Test void comparisonShowsRealTradeoffsAndNoPersonalizationBonus(){var f=population(1).get(0);double[] weights=Cohorts.preference(f);var base=terms(10000,15,0,36);var a=Personalizer.score(base,base,"PERSONAL_LOAN",f,weights);assertEquals(a,Personalizer.score(base,base,"PERSONAL_LOAN",f,weights));var longLoan=Personalizer.score(terms(10000,15,0,60),base,"PERSONAL_LOAN",f,weights);assertTrue(longLoan.monthlyPaymentUsd()<a.monthlyPaymentUsd());assertTrue(longLoan.totalCostUsd()>a.totalCostUsd());var lowApr=Personalizer.score(terms(10000,13,0,36),base,"PERSONAL_LOAN",f,weights);assertTrue(lowApr.fitScore()>a.fitScore());assertTrue(lowApr.simulatedAcceptance()>a.simulatedAcceptance());assertTrue(lowApr.totalCostUsd()<a.totalCostUsd());}
    /** Verifies that cards use explicit one year cost and zero APR loans are finite. */
    @Test void cardsUseExplicitOneYearCostAndZeroAprLoansAreFinite(){var f=population(1).get(0);var card=terms(10000,20,100,0);var m=Personalizer.score(card,card,"CREDIT_CARD",f,Cohorts.preference(f));assertEquals(2100,m.totalCostUsd(),.001);var zero=terms(12000,0,0,12);assertEquals(1000,Personalizer.score(zero,zero,"AUTO_LOAN",f,Cohorts.preference(f)).monthlyPaymentUsd(),.001);assertEquals(terms(10,1,0,12),Json.read(Json.write(terms(10,1,0,12)),Terms.class));}
    /** Verifies that candidate search respects catalog and demonstration envelope. */
    @Test void candidateSearchRespectsCatalogAndDemonstrationEnvelope(){var rows=population(120);var model=Cohorts.train("a",new Training("batch",42),rows);var ctx=Json.tree("{\"original\":{\"amountUsd\":10000,\"aprPct\":15,\"annualFeeUsd\":60,\"termMonths\":36},\"features\":{},\"response\":{\"qualification\":{\"product\":\"PERSONAL_LOAN\"},\"qualifiedOffer\":{\"data\":{\"minimumAmountUsd\":9500,\"maximumAmountUsd\":10500}}},\"policy\":{\"minimumAprPct\":{\"PERSONAL_LOAN\":6},\"maximumAprReductionPct\":2,\"maximumAmountChangePct\":10,\"minimumTermMonths\":12,\"maximumTermMonths\":84,\"maximumTermChangeMonths\":12}}");var candidates=Personalizer.candidates(ctx,model);assertFalse(candidates.isEmpty());assertTrue(candidates.size()<=108);for(var c:candidates){assertEquals(10000,c.terms().amountUsd().doubleValue());assertTrue(c.terms().aprPct().doubleValue()>=13);assertTrue(c.terms().termMonths()>=24&&c.terms().termMonths()<=48);}assertTrue(candidates.get(0).metrics().fitScore()>=candidates.get(candidates.size()-1).metrics().fitScore());}
}

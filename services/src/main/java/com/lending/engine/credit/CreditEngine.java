/**
 * Local credit-bureau service: opaque identity mapping, immutable independent reports and idempotent COBOL
 * credit decisions.
 */
package com.lending.engine.credit;

import static com.lending.engine.bureau.infrastructure.BureauDatabase.*;
import com.lending.engine.bureau.infrastructure.BureauDatabase;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.bureau.domain.Bureau;
import com.lending.engine.bureau.application.BureauPorts.CreditPolicy;
import com.lending.engine.domain.Model.*;
import com.lending.engine.domain.CustomerRules;
import com.lending.engine.infrastructure.Json;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Local credit-bureau service: opaque identity mapping, immutable independent reports and idempotent COBOL
 * credit decisions.
 */
public final class CreditEngine {
    private final BureauDatabase db;private final CreditPolicy policy;private final Clock clock;
    /** Initializes credit engine with the supplied configuration and dependencies. */
    public CreditEngine(BureauDatabase db,CreditPolicy policy,Clock clock){this.db=db;this.policy=policy;this.clock=clock;}
    /** Creates or returns the opaque bureau subject mapped to a customer identifier. */
    private String mapping(Connection c,String customerId)throws SQLException {
        Bureau.id(customerId,"customerId");execute(c,"INSERT INTO bureau_mapping VALUES(?,?) ON CONFLICT(customer_id) DO NOTHING",customerId,UUID.randomUUID().toString());
        return scalar(c,"SELECT subject_id FROM bureau_mapping WHERE customer_id=? FOR UPDATE",customerId);
    }
    /** Loads the most recent independent report for a bureau subject. */
    private BureauProfile latest(Connection c,String subject)throws SQLException{return one(c,"SELECT payload FROM bureau_profiles WHERE subject_id=? ORDER BY version DESC LIMIT 1",BureauProfile.class,subject);}
    /** Loads or locally generates the customer independent bureau report. */
    public BureauProfile profile(String customerId){return db.tx(c->{String subject=mapping(c,customerId);var profile=latest(c,subject);if(profile==null){profile=new BureauProfile(subject,customerId,1,"SYNTHETIC-BUREAU-2026-001",clock.instant().toString(),generate(customerId));save(c,profile);}return profile;});}
    /** Retrieves immutable bureau report revisions for a customer. */
    public List<BureauProfile> history(String customerId,int after,int limit){if(after<0||limit<1||limit>100)throw new Problem(422,"Invalid history page");return db.tx(c->{var rows=new ArrayList<BureauProfile>();try(var p=statement(c,"SELECT p.payload FROM bureau_profiles p JOIN bureau_mapping m ON m.subject_id=p.subject_id WHERE m.customer_id=? AND p.version>? ORDER BY p.version LIMIT ?",customerId,after,limit);var r=p.executeQuery()){while(r.next())rows.add(Json.read(r.getString(1),BureauProfile.class));}return rows;});}
    /** Imports or edits a report only when the expected current version matches. */
    public BureauProfile change(String customerId,ProfileChange request){
        if(request.expectedVersion()==null||request.expectedVersion()<0||!Set.of("LOCAL_EDIT","LOCAL_IMPORT").contains(Objects.toString(request.source(),"")))throw new Problem(422,"Use expectedVersion and source LOCAL_EDIT or LOCAL_IMPORT");validate(request.facts());
        return db.tx(c->{String subject=mapping(c,customerId);var old=latest(c,subject);int version=old==null?0:old.version();if(request.expectedVersion()!=version)throw new Problem(409,"Bureau profile changed; reload its version");var profile=new BureauProfile(subject,customerId,version+1,request.source(),clock.instant().toString(),request.facts());save(c,profile);return profile;});
    }
    /** Persists the new independent bureau report revision. */
    private void save(Connection c,BureauProfile p)throws SQLException{execute(c,"INSERT INTO bureau_profiles VALUES(?,?,?)",p.subjectId(),p.version(),Json.write(p));}
    /**
     * Produces an idempotent COBOL credit assessment from validated customer inputs and independent bureau
     * facts.
     */
    public Assessment assess(CreditInput input){validate(input);String fingerprint=hash(input);
        return db.tx(c->{execute(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,73103))",input.requestId());var old=one(c,"SELECT payload FROM credit_assessments WHERE request_id=?",Assessment.class,input.requestId());
            if(old!=null){if(!fingerprint.equals(scalar(c,"SELECT fingerprint FROM credit_assessments WHERE request_id=?",input.requestId())))throw new Problem(409,"Credit requestId reused with different inputs");return old;}
            String subject=mapping(c,input.customerId());if(!subject.equals(input.subjectId()))throw new Problem(409,"Bureau subject does not map to this customer");var profile=latest(c,subject);if(profile==null)throw new Problem(409,"Generate or import a bureau profile first");
            var decision=policy.evaluate(input,profile);var now=clock.instant();var assessment=new Assessment(UUID.randomUUID().toString(),input.requestId(),subject,profile.version(),decision.policyVersion(),decision.outcome(),decision.reasons(),now.toString(),now.plus(Duration.ofDays(7)).toString(),input,profile);
            execute(c,"INSERT INTO credit_assessments VALUES(?,?,?)",input.requestId(),fingerprint,Json.write(assessment));return assessment;});
    }
    /** Builds deterministic local bureau facts independently of the customer self-reported credit score. */
    private Facts generate(String customerId){var random=new Random(Long.parseUnsignedLong(hash("BUREAU-2026-001:"+customerId).substring(0,16),16));int file=random.nextInt(100);return new Facts(file<4?FileStatus.NO_HIT:file<7?FileStatus.FROZEN:file<9?FileStatus.AMBIGUOUS:FileStatus.MATCHED,580+random.nextInt(271),BigDecimal.valueOf(200+random.nextInt(2601)),random.nextInt(101),random.nextInt(100)<80?0:random.nextInt(6),random.nextInt(9),random.nextInt(241),random.nextInt(100)<3,clock.instant().toString());}
    /** Validates the required identity, provenance and numeric bounds at the credit-service boundary. */
    private void validate(Facts f){if(f==null||f.fileStatus()==null)throw new Problem(422,"Bureau facts and fileStatus are required");CustomerRules.money(f.monthlyDebtUsd(),"monthlyDebtUsd");range(f.creditScore(),300,850,"creditScore");range(f.utilizationPct(),0,100,"utilizationPct");range(f.delinquencies12m(),0,99,"delinquencies12m");range(f.inquiries6m(),0,99,"inquiries6m");range(f.oldestAccountMonths(),0,1200,"oldestAccountMonths");
        if(f.reportedAt()!=null)try{if(Instant.parse(f.reportedAt()).isAfter(clock.instant()))throw new IllegalArgumentException();}catch(Exception e){throw new Problem(422,"reportedAt must be a nonfuture UTC timestamp");}}
    /** Validates the required identity, provenance and numeric bounds at the credit-service boundary. */
    private void validate(CreditInput i){Bureau.id(i.requestId(),"requestId");Bureau.id(i.subjectId(),"subjectId");Bureau.id(i.customerId(),"customerId");if(i.product()==null||i.amountUsd()==null||i.aprPct()==null||i.termMonths()==null||i.incomeSource()==null)throw new Problem(422,"Credit product, amount, APR, term and income provenance are required");CustomerRules.money(i.amountUsd(),"amountUsd");CustomerRules.money(i.monthlyIncomeUsd(),"monthlyIncomeUsd");CustomerRules.money(i.vehicleValueUsd(),"vehicleValueUsd");CustomerRules.money(i.aprPct(),"aprPct");if(i.aprPct().compareTo(BigDecimal.valueOf(100))>0||i.termMonths()<0||i.termMonths()>120||i.product()!=Product.CREDIT_CARD&&i.termMonths()==0||i.product()==Product.CREDIT_CARD&&i.termMonths()!=0)throw new Problem(422,"Invalid product term or APR");}
    /** Rejects a numeric value outside the supported inclusive bounds. */
    private static void range(Integer value,int min,int max,String name){if(value!=null&&(value<min||value>max))throw new Problem(422,"Invalid "+name);}
}

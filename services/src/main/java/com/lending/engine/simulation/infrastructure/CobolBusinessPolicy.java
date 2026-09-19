/**
 * Bounded file/stream adapter for the same COBOL form rules used in isolated simulation and published
 * offer decisions.
 */
package com.lending.engine.simulation.infrastructure;
import com.lending.engine.simulation.domain.BusinessRules.*;
import com.lending.engine.simulation.domain.BusinessRules.Result;
import com.lending.engine.simulation.domain.BusinessRules;
import com.lending.engine.domain.Model.*;
import com.lending.engine.bureau.domain.Bureau.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Bounded file/stream adapter for the same COBOL form rules used in isolated simulation and published
 * offer decisions.
 */
public final class CobolBusinessPolicy {
    private final Path binary,scratch;
    private static final String[] REASONS={"MISSING_DATA","BUREAU_FILE_NOT_MATCHED","STALE_BUREAU_REPORT","THIN_BUREAU_FILE","LOW_INCOME","LOW_SCORE","HIGH_DTI","HIGH_UTILIZATION","DELINQUENCIES","AMOUNT_LIMIT","VEHICLE_AGE","HIGH_LTV","TENURE","EXISTING_PRODUCT","INQUIRIES","BANKRUPTCY","CONSENT_REQUIRED","PRESCREEN_OPT_OUT_OR_UNKNOWN","GLOBAL_SUPPRESSION"};
    /** Initializes COBOL business policy with the supplied configuration and dependencies. */
    public CobolBusinessPolicy(Path root){binary=root.resolve("build/lirlbatch");scratch=root.resolve("runtime/scratch");}
    /** Evaluates versioned underwriting, marketing or bureau form rules using the compiled COBOL adapter. */
    public List<Result> evaluate(List<String> requests){if(requests.isEmpty())return List.of();if(requests.size()>60000)throw new Problem(422,"Rule batch exceeds 60000 records");Path dir=null;Process process=null;try{
        Files.createDirectories(scratch);dir=Files.createTempDirectory(scratch,"rules-");Path input=dir.resolve("input"),output=dir.resolve("output");Files.write(input,requests,StandardCharsets.US_ASCII);
        process=new ProcessBuilder(binary.toString()).redirectInput(input.toFile()).redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Business rule evaluation timed out");}
        var lines=Files.readAllLines(output,StandardCharsets.US_ASCII);if(process.exitValue()!=0||lines.size()!=requests.size())throw new IllegalStateException("Incomplete COBOL rule result");var results=new ArrayList<Result>();for(var line:lines){if(!line.matches("[EDI][01]{19}"))throw new IllegalStateException("Invalid COBOL rule response");var reasons=new ArrayList<String>();for(int i=0;i<19;i++)if(line.charAt(i+1)=='1')reasons.add(REASONS[i]);results.add(new Result(line.charAt(0)=='E'?"ELIGIBLE":line.charAt(0)=='I'?"INCOMPLETE":"INELIGIBLE",List.copyOf(reasons)));}return List.copyOf(results);
    }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Business rule evaluation interrupted",e);}catch(java.io.IOException e){throw new IllegalStateException("Build the Business COBOL runtime first",e);}finally{if(process!=null&&process.isAlive())process.destroyForcibly();if(dir!=null)try{Files.deleteIfExists(dir.resolve("input"));Files.deleteIfExists(dir.resolve("output"));Files.deleteIfExists(dir);}catch(java.io.IOException ignored){}}}
    /** Builds customer-stage facts for the shared Business rule evaluator. */
    public Result customer(CustomerInput data,Product product,Stage rules,String stage,boolean suppressed){return evaluate(List.of(customerRecord(data,product,rules,stage,suppressed))).get(0);}
    /** Selects the customer requested amount for the specified lending product. */
    public static BigDecimal amount(CustomerInput d,Product p){return switch(p){case PERSONAL_LOAN->d.personalLoanAmountUsd();case CREDIT_CARD->d.requestedCardLimitUsd();case AUTO_LOAN->d.autoLoanAmountUsd();};}
    /** Encodes customer facts and configured rules into the fixed-width COBOL request. */
    public static String customerRecord(CustomerInput d,Product p,Stage r,String stage,boolean suppressed){
        boolean uw=stage.equals("U");boolean complete=amount(d,p)!=null&&(!uw||d.monthlyIncomeUsd()!=null&&d.monthlyDebtPaymentsUsd()!=null&&d.creditScore()!=null&&d.delinquencies12m()!=null)
            &&(r.minimumScore()==0||d.creditScore()!=null)&&(r.minimumIncomeUsd().signum()==0||d.monthlyIncomeUsd()!=null)&&(r.maximumDtiPct()==999||d.monthlyIncomeUsd()!=null&&d.monthlyDebtPaymentsUsd()!=null)
            &&(r.maximumUtilizationPct()==100&&p!=Product.CREDIT_CARD||d.creditUtilizationPct()!=null)&&(r.minimumTenureMonths()==0||d.bankingTenureMonths()!=null)
            &&(r.maximumDelinquencies()==99||d.delinquencies12m()!=null)&&(!r.excludeExistingProduct()||d.existingProducts()!=null)&&(p!=Product.AUTO_LOAN||d.vehicleValueUsd()!=null&&(r.maximumVehicleAge()==99||d.vehicleAgeYears()!=null));
        return encode(stage,p,complete,true,d.marketingOptIn(),d.prescreenOptOut(),suppressed,d.existingProducts()!=null&&d.existingProducts().contains(p.name()),d.monthlyIncomeUsd(),d.monthlyDebtPaymentsUsd(),d.creditScore(),d.creditUtilizationPct(),d.delinquencies12m(),amount(d,p),d.vehicleValueUsd(),d.vehicleAgeYears(),d.bankingTenureMonths(),0,1200,0,false,BigDecimal.ZERO,36,r);
    }
    /** Builds independent bureau-stage facts for the shared Business rule evaluator. */
    public Result bureau(CreditInput input,BureauProfile profile,Stage rules,Instant now){return evaluate(List.of(bureauRecord(input,profile.facts(),rules,now))).get(0);}
    /** Encodes independent bureau facts and configured rules into the fixed-width COBOL request. */
    public static String bureauRecord(CreditInput i,Facts f,Stage r,Instant now){boolean complete=i.monthlyIncomeUsd()!=null&&f.creditScore()!=null&&f.monthlyDebtUsd()!=null&&f.utilizationPct()!=null&&f.delinquencies12m()!=null&&f.inquiries6m()!=null&&f.oldestAccountMonths()!=null&&f.bankruptcy()!=null&&f.reportedAt()!=null&&(i.product()!=Product.AUTO_LOAN||i.vehicleValueUsd()!=null);long age=f.reportedAt()==null?99999:Math.max(0,Math.min(99999,Duration.between(Instant.parse(f.reportedAt()),now).toDays()));
        return encode("B",i.product(),complete,f.fileStatus()==FileStatus.MATCHED,true,false,false,false,i.monthlyIncomeUsd(),f.monthlyDebtUsd(),f.creditScore(),f.utilizationPct(),f.delinquencies12m(),i.amountUsd(),i.vehicleValueUsd(),0,1200,f.inquiries6m(),f.oldestAccountMonths(),age,f.bankruptcy(),i.aprPct(),i.termMonths(),r);}
    /** Encodes a rule-evaluation request using the versioned COBOL fixed-record contract. */
    private static String encode(String stage,Product p,boolean complete,boolean matched,Boolean consent,Boolean optout,boolean suppressed,boolean held,Number income,Number debt,Number score,Number util,Number delinq,Number amount,Number vehicle,Number age,Number tenure,Number inquiries,Number history,Number report,Boolean bankrupt,Number apr,Number term,Stage r){BusinessRules.validate(r);
        return stage+switch(p){case PERSONAL_LOAN->"PL";case CREDIT_CARD->"CC";case AUTO_LOAN->"AL";}+yn(complete)+yn(matched)+yn(consent)+(optout==null?"?":yn(optout))+yn(suppressed)+yn(held)
            +n(income,11,2)+n(debt,11,2)+n(score,3,0)+n(util,5,2)+n(delinq,2,0)+n(amount,11,2)+n(vehicle,11,2)+n(age,2,0)+n(tenure,4,0)+n(inquiries,2,0)+n(history,4,0)+n(report,5,0)+yn(bankrupt)+n(apr,5,2)+n(term,3,0)
            +n(r.minimumScore(),3,0)+n(r.minimumIncomeUsd(),11,2)+n(r.maximumDtiPct(),3,0)+n(r.maximumUtilizationPct(),3,0)+n(r.maximumDelinquencies(),2,0)+n(r.maximumAmountUsd(),11,2)+n(r.incomeMultiple(),3,0)+n(r.maximumLtvPct(),4,0)+n(r.maximumVehicleAge(),2,0)+n(r.minimumTenureMonths(),4,0)+yn(r.excludeExistingProduct())+n(r.maximumInquiries(),2,0)+n(r.minimumHistoryMonths(),4,0)+n(r.maximumReportAgeDays(),5,0)+yn(r.allowBankruptcy());}
    /** Encodes a boolean condition as a COBOL Y/N flag. */
    private static String yn(Boolean b){return Boolean.TRUE.equals(b)?"Y":"N";}
    /** Formats a numeric policy value into the requested fixed-width COBOL field. */
    private static String n(Number value,int width,int scale){var d=value==null?BigDecimal.ZERO:new BigDecimal(value.toString());String s=d.movePointRight(scale).setScale(0,java.math.RoundingMode.HALF_UP).toPlainString();if(d.signum()<0||s.length()>width)throw new Problem(422,"Value exceeds rule copybook");return "0".repeat(width-s.length())+s;}
}

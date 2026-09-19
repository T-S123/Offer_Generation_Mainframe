/** Validated fixed-record transport; actual COBOL performs qualification and ordered quota allocation. */
package com.lending.engine.marketing.infrastructure;

import com.lending.engine.marketing.application.MarketingPorts.Qualifier;
import com.lending.engine.marketing.domain.Marketing;
import com.lending.engine.marketing.domain.Marketing.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Validated fixed-record transport; actual COBOL performs qualification and ordered quota allocation. */
public final class CobolMarketingQualifier implements Qualifier {
    private final Path executable,scratch;
    private static final List<String> REASONS=List.of("NOT_IN_PRESCREEN","SOURCE_CHANGED","UNDERWRITING_NOT_ELIGIBLE","UNDERWRITING_EXPIRED",
        "MARKETING_CONSENT_REQUIRED","PRESCREEN_OPT_OUT_OR_UNKNOWN","GLOBAL_SUPPRESSION","CAMPAIGN_INACTIVE","CAMPAIGN_OUTSIDE_DATES",
        "OFFER_INACTIVE","OFFER_OUTSIDE_DATES","TENURE_UNKNOWN","TENURE_BELOW_MINIMUM","EXISTING_PRODUCT","PRODUCT_OWNERSHIP_UNKNOWN",
        "AMOUNT_UNKNOWN","AMOUNT_OUTSIDE_OFFER","CUSTOMER_COOLDOWN","CUSTOMER_WINDOW_LIMIT","CAMPAIGN_CAPACITY","ALREADY_RESERVED");
    /** Initializes COBOL marketing qualifier with the supplied configuration and dependencies. */
    public CobolMarketingQualifier(Path executable,Path scratch){
        this.executable=executable.toAbsolutePath();this.scratch=scratch;
        if(!Files.isExecutable(this.executable))throw new IllegalStateException("Compile marketing COBOL first: scripts/build.sh");
    }
    /** Streams ordered marketing facts through COBOL and decodes qualification and throttle reason flags. */
    public List<RuleResult> evaluate(List<RuleInput> inputs) {
        if(inputs.isEmpty())return List.of();
        List<Path> files=new ArrayList<>();Process process=null;
        try{
            Files.createDirectories(scratch);
            Path input=Files.createTempFile(scratch,"mk-",".in");files.add(input);
            Path output=Files.createTempFile(scratch,"mk-",".out");files.add(output);
            Path errors=Files.createTempFile(scratch,"mk-",".err");files.add(errors);
            try(var writer=Files.newBufferedWriter(input,StandardCharsets.US_ASCII)){
                for(int i=0;i<inputs.size();i++){writer.write(encode(i+1,inputs.get(i)));writer.newLine();}
            }
            var pb=new ProcessBuilder(executable.toString());pb.environment().put("DD_LIREQ",input.toAbsolutePath().toString());pb.environment().put("DD_LIRSP",output.toAbsolutePath().toString());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);pb.redirectError(errors.toFile());process=pb.start();
            if(!process.waitFor(90,TimeUnit.SECONDS) || process.exitValue()!=0)throw new IllegalStateException("Marketing COBOL failed; no run or reservations saved");
            var lines=Files.readAllLines(output,StandardCharsets.US_ASCII);
            if(lines.size()!=inputs.size())throw new IllegalStateException("Incomplete marketing response");
            List<RuleResult> results=new ArrayList<>();
            for(int i=0;i<lines.size();i++){
                String line=lines.get(i);
                if(line.length()!=42 || !line.substring(0,8).equals(digits(i+1,8)) || !line.substring(21).matches("[01]{21}")
                    || !line.substring(9,21).trim().equals(Marketing.RULE_VERSION) || "QX".indexOf(line.charAt(8))<0)throw new IllegalStateException("Malformed marketing response");
                List<String> reasons=new ArrayList<>();for(int j=0;j<21;j++)if(line.charAt(21+j)=='1')reasons.add(REASONS.get(j));
                boolean qualified=line.charAt(8)=='Q';if(qualified!=reasons.isEmpty())throw new IllegalStateException("Inconsistent marketing response");
                results.add(new RuleResult(qualified,List.copyOf(reasons),Marketing.RULE_VERSION));
            }
            return results;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Marketing interrupted",e);}
        catch(java.io.IOException e){throw new IllegalStateException("Marketing runtime unavailable",e);}
        finally{if(process!=null && process.isAlive())process.destroyForcibly();for(Path f:files)try{Files.deleteIfExists(f);}catch(Exception ignored){}}
    }
    /** Encodes marketing facts, versioned limits and reservation context into the COBOL request layout. */
    public static String encode(int sequence,RuleInput r){
        String value=digits(sequence,8)+digits(r.customerOrdinal(),8)+digits(r.campaignSlot(),2)
            +flag(r.memberPresent())+flag(r.sourceCurrent())+flag(r.underwritingEligible())+flag(r.underwritingCurrent())
            +flag(r.marketingOptIn())+flag(r.prescreenOptOut())+flag(r.suppressed())+flag(r.campaignActive())+flag(r.campaignInDate())+flag(r.offerActive())+flag(r.offerInDate())
            +flag(r.tenureMonths()!=null)+digits(r.tenureMonths()==null?0:r.tenureMonths(),4)+digits(r.minimumTenureMonths(),4)
            +flag(r.productHeld())+flag(r.excludeExistingProduct())+flag(r.amount()!=null)
            +digits(r.amount()==null?0:r.amount().movePointRight(2).longValueExact(),11)
            +digits(r.minimumAmount().movePointRight(2).longValueExact(),11)+digits(r.maximumAmount().movePointRight(2).longValueExact(),11)
            +flag(r.secondsSinceReservation()!=null)+digits(r.secondsSinceReservation()==null?0:Math.min(9999999999L,r.secondsSinceReservation()),10)
            +digits(Math.min(99999,r.windowReservations()),5)+digits(Math.min(99999,r.campaignReservations()),5)+digits(r.campaignCapacity(),5)
            +digits(r.cooldownDays(),3)+digits(r.maximumReservations(),5)+flag(r.alreadyReserved());
        if(value.length()!=109 || r.campaignSlot()<1 || r.campaignSlot()>20)throw new IllegalArgumentException("Invalid marketing record bounds");return value;
    }
    /** Converts a condition into the single-character flag expected by COBOL. */
    private static String flag(Boolean value){return value==null?"U":value?"Y":"N";}
    /** Formats a numeric field to the required fixed width for COBOL transport. */
    private static String digits(long value,int width){String s=Long.toString(value);if(value<0 || s.length()>width)throw new IllegalArgumentException("Numeric record overflow");return "0".repeat(width-s.length())+s;}
}

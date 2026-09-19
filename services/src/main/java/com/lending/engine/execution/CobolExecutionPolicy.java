/**
 * Versioned fixed-record adapter; final suppression/frequency decisions execute in COBOL without a Java
 * fallback.
 */
package com.lending.engine.execution;
import com.lending.engine.infrastructure.Json;
import com.lending.engine.domain.Model.*;
import com.lending.engine.marketing.domain.Marketing.Suppression;
import static com.lending.engine.execution.Execution.*;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.hash;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Versioned fixed-record adapter; final suppression/frequency decisions execute in COBOL without a Java
 * fallback.
 */
public final class CobolExecutionPolicy {
    public final Policy policy;public final String version;private final Path binary;
    /** Initializes COBOL execution policy with the supplied configuration and dependencies. */
    public CobolExecutionPolicy(Path root){binary=root.resolve("build/liexbatch");try{policy=Json.read(Files.readString(root.resolve("app/data/campaign-execution-policy.json")),Policy.class);}catch(Exception e){throw new IllegalStateException("Execution policy unavailable",e);}if(!policy.version().equals("CEX-2026-001")||policy.cooldownSeconds()<0||policy.cooldownSeconds()>31536000||policy.windowDays()<1||policy.windowDays()>365||policy.maximumPackages()<1||policy.maximumPackages()>99999)throw new IllegalArgumentException("Invalid execution policy");version=policy.version()+"-"+hash(policy).substring(0,12);}
    /** Executes COBOL consent, suppression, channel and outbound-frequency checks for a campaign package. */
    public String evaluate(CustomerInput customer,Suppression suppression,boolean qualified,boolean channel,History history,Instant now){boolean suppressed=suppression!=null&&Boolean.TRUE.equals(suppression.data().active())&&(suppression.data().expiresOn()==null||!LocalDate.parse(suppression.data().expiresOn()).isBefore(now.atZone(ZoneOffset.UTC).toLocalDate()));String request=(Boolean.TRUE.equals(customer.marketingOptIn())?"Y":"N")+(Boolean.FALSE.equals(customer.prescreenOptOut())?"N":"Y")+(suppressed?"Y":"N")+(qualified?"Y":"N")+(channel?"Y":"N")+(history.sameDispatch()?"Y":"N")+String.format(Locale.ROOT,"%010d%010d%05d%05d",Math.min(9999999999L,Math.max(0,history.secondsSinceLast())),policy.cooldownSeconds(),Math.min(99999,history.windowCount()),policy.maximumPackages());
        Process process=null;try{process=new ProcessBuilder(binary.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();try(var out=process.getOutputStream()){out.write((request+"\n").getBytes(StandardCharsets.US_ASCII));}if(!process.waitFor(10,TimeUnit.SECONDS))throw new IllegalStateException("Execution COBOL timed out");String result=new String(process.getInputStream().readNBytes(128),StandardCharsets.US_ASCII).trim();if(process.exitValue()!=0||!Set.of("APPROVED","MARKETING_CONSENT_REQUIRED","PRESCREEN_OPT_OUT_OR_UNKNOWN","GLOBAL_SUPPRESSION","OFFER_NOT_CURRENT","NO_ENABLED_CHANNEL","EXECUTION_COOLDOWN","EXECUTION_WINDOW_LIMIT").contains(result))throw new IllegalStateException("Invalid execution COBOL response");return result;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}catch(Exception e){throw new IllegalStateException("Execution COBOL unavailable",e);}finally{if(process!=null&&process.isAlive())process.destroyForcibly();}}
}

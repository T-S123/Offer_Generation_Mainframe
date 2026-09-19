/**
 * Bounded COBOL workers evaluate legacy bureau policy or immutable published rule forms; failures never
 * fall back to Java credit rules.
 */
package com.lending.engine.credit;

import com.lending.engine.bureau.application.BureauPorts.*;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.bureau.domain.Bureau;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Bounded COBOL workers evaluate legacy bureau policy or immutable published rule forms; failures never
 * fall back to Java credit rules.
 */
public final class CobolCreditPolicy implements CreditPolicy,AutoCloseable {
    private static final String[] REASONS={"MISSING_DATA","BUREAU_FILE_REVIEW","STALE_BUREAU_REPORT","THIN_CREDIT_FILE","SCORE_BELOW_MINIMUM","DEBT_TO_INCOME_EXCEEDED","UTILIZATION_EXCEEDED","DELINQUENCY_EXCEEDED","INQUIRIES_EXCEEDED","BANKRUPTCY_REPORTED","LOAN_TO_VALUE_EXCEEDED","AMOUNT_OUTSIDE_POLICY"};
    private final ArrayBlockingQueue<Worker> available;private final List<Worker> all=new ArrayList<>();private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();private final Clock clock;
    private final com.lending.engine.simulation.infrastructure.CobolBusinessPolicy business;
    private final Semaphore businessSlots;
    /** Initializes COBOL credit policy with the supplied configuration and dependencies. */
    public CobolCreditPolicy(Path executable,int count,Clock clock){this.clock=clock;business=new com.lending.engine.simulation.infrastructure.CobolBusinessPolicy(executable.toAbsolutePath().getParent().getParent());businessSlots=new Semaphore(count);available=new ArrayBlockingQueue<>(count);try{for(int i=0;i<count;i++){var w=new Worker(executable);all.add(w);available.add(w);}}catch(RuntimeException e){close();throw e;}}
    /** Evaluates credit requests with bounded COBOL workers or the published offer-rule evaluator. */
    public Decision evaluate(CreditInput input,BureauProfile profile){Worker worker=null;ScheduledFuture<?> timeout=null;var inFlight=new java.util.concurrent.atomic.AtomicBoolean(true);
        if(input.offerRules()!=null){com.lending.engine.simulation.domain.BusinessRules.validate(input.offerRules());if(!businessSlots.tryAcquire())throw new IllegalStateException("Business credit policy busy");try{var r=business.bureau(input,profile,input.offerRules().bureau(),clock.instant());return new Decision(r.eligible()?"APPROVED":r.status().equals("INCOMPLETE")?"REVIEW":"DECLINED",r.reasons(),input.offerRules().id()+"-v"+input.offerRules().version());}finally{businessSlots.release();}}
        try{worker=available.poll(5,TimeUnit.SECONDS);if(worker==null)throw new IllegalStateException("Credit policy busy");Worker active=worker;timeout=timer.schedule(()->{synchronized(active){if(inFlight.get())active.kill();}},5,TimeUnit.SECONDS);
            worker.ensure();worker.writer.write(encode(input,profile,clock.instant()));worker.writer.newLine();worker.writer.flush();String line=worker.reader.readLine();
            if(line==null||line.length()!=25||!line.substring(1,13).equals(Bureau.POLICY)||!line.substring(13).matches("[01]{12}"))throw new IllegalStateException("Malformed COBOL credit response");
            String outcome=switch(line.charAt(0)){case 'A'->"APPROVED";case 'D'->"DECLINED";case 'R'->"REVIEW";default->throw new IllegalStateException("Invalid credit outcome");};
            var reasons=new ArrayList<String>();for(int i=0;i<12;i++)if(line.charAt(13+i)=='1')reasons.add(REASONS[i]);
            if(outcome.equals("APPROVED")!=reasons.isEmpty())throw new IllegalStateException("Inconsistent credit response");return new Decision(outcome,reasons,line.substring(1,13));
        }catch(InterruptedException e){if(worker!=null)worker.kill();Thread.currentThread().interrupt();throw new IllegalStateException(e);}catch(IOException|RuntimeException e){if(worker!=null)worker.kill();throw new IllegalStateException("COBOL credit runtime failed",e);}
        finally{if(worker!=null){synchronized(worker){inFlight.set(false);if(timeout!=null)timeout.cancel(false);}available.offer(worker);}}
    }
    /** Encodes bureau facts, requested terms and policy inputs into a fixed-width credit record. */
    public static String encode(CreditInput input,BureauProfile profile,Instant now){var f=profile.facts();boolean complete=input.monthlyIncomeUsd()!=null&&f.creditScore()!=null&&f.monthlyDebtUsd()!=null&&f.utilizationPct()!=null&&f.delinquencies12m()!=null&&f.inquiries6m()!=null&&f.oldestAccountMonths()!=null&&f.bankruptcy()!=null&&f.reportedAt()!=null&&(input.product()!=com.lending.engine.domain.Model.Product.AUTO_LOAN||input.vehicleValueUsd()!=null);
        long age=f.reportedAt()==null?99999:Math.min(99999,Math.max(0,Duration.between(Instant.parse(f.reportedAt()),now).toDays()));
        String value=switch(input.product()){case PERSONAL_LOAN->"PL";case CREDIT_CARD->"CC";case AUTO_LOAN->"AL";};
        value+=(complete?"Y":"N")+(f.fileStatus()==FileStatus.MATCHED?"Y":"N")+digits(age,5,0)+digits(input.monthlyIncomeUsd(),11,2)+digits(f.monthlyDebtUsd(),11,2)+digits(input.amountUsd(),11,2)+digits(input.vehicleValueUsd(),11,2)+digits(f.creditScore(),3,0)+digits(f.utilizationPct(),3,0)+digits(f.delinquencies12m(),2,0)+digits(f.inquiries6m(),2,0)+digits(f.oldestAccountMonths(),4,0)+(Boolean.TRUE.equals(f.bankruptcy())?"Y":"N")+digits(input.termMonths(),3,0)+digits(input.aprPct(),5,2);
        if(value.length()!=76)throw new IllegalArgumentException("Credit input exceeds copybook");return value;
    }
    /** Formats a numeric field to the required fixed width for COBOL transport. */
    private static String digits(Number value,int width,int scale){long n=value==null?0:new BigDecimal(value.toString()).movePointRight(scale).longValueExact();if(n<0)throw new IllegalArgumentException("Unsigned credit field");return String.format(Locale.ROOT,"%0"+width+"d",n);}
    /** Groups worker behavior for COBOL credit policy operations. */
    private static final class Worker {
        final Path executable;Process process;BufferedWriter writer;BufferedReader reader;
        /** Initializes worker with the supplied configuration and dependencies. */
        Worker(Path executable){this.executable=executable;ensure();}
        /** Starts or restores the persistent COBOL worker process when needed. */
        synchronized void ensure(){if(process!=null&&process.isAlive())return;try{process=new ProcessBuilder(executable.toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();writer=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.US_ASCII));reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.US_ASCII));}catch(IOException e){throw new IllegalStateException("Build LICBAT01 before starting credit service",e);}}
        /** Terminates the persistent COBOL process and releases its streams. */
        synchronized void kill(){if(process!=null)process.destroyForcibly();}
    }
    /** Releases the resources owned by this component. */
    public void close(){timer.shutdownNow();all.forEach(Worker::kill);}
}

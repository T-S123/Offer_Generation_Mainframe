/** Fixed-record adapter to the compiled COBOL batch program; there is no Java policy fallback. */
package com.lending.engine.infrastructure;

import com.lending.engine.application.Ports.Underwriter;
import com.lending.engine.domain.Model.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fixed-record adapter to the compiled COBOL batch program; there is no Java policy fallback. */
public final class CobolUnderwriter implements Underwriter {
    private final Path executable, scratch;
    private static final String[] REASONS = {"MISSING_REQUIRED_DATA", "INCOME_BELOW_MINIMUM", "CREDIT_SCORE_BELOW_MINIMUM",
        "DEBT_TO_INCOME_EXCEEDED", "DELINQUENCY_LIMIT_EXCEEDED", "UTILIZATION_LIMIT_EXCEEDED",
        "REQUESTED_AMOUNT_OUTSIDE_POLICY", "VEHICLE_TOO_OLD", "LOAN_TO_VALUE_EXCEEDED", "UNSUPPORTED_PRODUCT"};
    /** Initializes COBOL underwriter with the supplied configuration and dependencies. */
    public CobolUnderwriter(Path executable, Path scratch) {
        this.executable = executable.toAbsolutePath(); this.scratch = scratch;
        if (!Files.isExecutable(this.executable)) throw new IllegalStateException("Compile COBOL first: scripts/build.sh");
    }
    /** Runs the compiled COBOL underwriting program for a batch and decodes product-level decisions. */
    public List<List<RuleResult>> evaluate(List<CustomerInput> customers) {
        Path input = null, output = null, errors = null;
        Process process = null;
        try {
            Files.createDirectories(scratch);
            input = Files.createTempFile(scratch, "uw-", ".in");
            output = Files.createTempFile(scratch, "uw-", ".out");
            errors = Files.createTempFile(scratch, "uw-", ".err");
            try (var writer = Files.newBufferedWriter(input, StandardCharsets.US_ASCII)) {
                int key = 0;
                for (CustomerInput c : customers) for (Product p : Product.values()) {
                    writer.write(encode(++key, p, c)); writer.newLine();
                }
            }
            ProcessBuilder pb = new ProcessBuilder(executable.toString());
            pb.environment().put("DD_LIREQ", input.toAbsolutePath().toString());
            pb.environment().put("DD_LIRSP", output.toAbsolutePath().toString());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(errors.toFile());
            process = pb.start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) throw new IllegalStateException("COBOL evaluation timed out");
            if (process.exitValue() != 0) throw new IllegalStateException("COBOL evaluation failed; no decisions saved");
            List<String> lines = Files.readAllLines(output, StandardCharsets.US_ASCII);
            if (lines.size() != customers.size() * 3) throw new IllegalStateException("Incomplete COBOL response");
            List<List<RuleResult>> results = new ArrayList<>();
            for (int i = 0; i < customers.size(); i++) {
                List<RuleResult> group = new ArrayList<>();
                for (int p = 0; p < 3; p++) {
                    int key = i * 3 + p + 1;
                    String line = lines.get(key - 1);
                    Product product = Product.values()[p];
                    if (line.length() != 33 || !line.substring(0,8).equals(String.format(Locale.ROOT,"%08d",key))
                        || !line.substring(8,10).equals(code(product)) || !line.substring(23).matches("[01]{10}"))
                        throw new IllegalStateException("Malformed COBOL response");
                    Status status = switch (line.charAt(10)) { case 'E' -> Status.ELIGIBLE; case 'D' -> Status.INELIGIBLE;
                        case 'I' -> Status.INCOMPLETE; default -> throw new IllegalStateException("Invalid COBOL status"); };
                    List<String> reasons = new ArrayList<>();
                    for (int n = 0; n < 10; n++) if (line.charAt(23+n) == '1') reasons.add(REASONS[n]);
                    String policy = line.substring(11,23).trim();
                    if (policy.isEmpty() || (status == Status.ELIGIBLE && !reasons.isEmpty())
                        || (status != Status.ELIGIBLE && reasons.isEmpty())) throw new IllegalStateException("Inconsistent COBOL response");
                    group.add(new RuleResult(product, status, reasons, policy));
                }
                results.add(group);
            }
            return results;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Evaluation interrupted", e); }
        catch (java.io.IOException e) { throw new IllegalStateException("COBOL runtime unavailable", e); }
        finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            for (Path f : new Path[]{input,output,errors}) if (f != null) try { Files.deleteIfExists(f); } catch (Exception ignored) {}
        }
    }
    /** Encodes one customer and product into the underwriting request copybook layout. */
    public static String encode(int key, Product product, CustomerInput c) {
        BigDecimal amount = switch(product) { case PERSONAL_LOAN -> c.personalLoanAmountUsd();
            case CREDIT_CARD -> c.requestedCardLimitUsd(); case AUTO_LOAN -> c.autoLoanAmountUsd(); };
        String result = String.format(Locale.ROOT,"%08d",key) + code(product)
            + field(c.monthlyIncomeUsd(),11,2) + field(c.monthlyDebtPaymentsUsd(),11,2)
            + field(c.creditScore(),3,0) + field(c.delinquencies12m(),2,0)
            + field(c.creditUtilizationPct(),5,2) + field(amount,11,2)
            + field(c.vehicleValueUsd(),11,2) + field(c.vehicleAgeYears(),2,0);
        if (result.length() != 74) throw new IllegalArgumentException("Input exceeds COBOL record bounds");
        return result;
    }
    /** Maps the lending product to the two-character COBOL product code. */
    private static String code(Product product) { return switch(product) { case PERSONAL_LOAN -> "PL"; case CREDIT_CARD -> "CC"; case AUTO_LOAN -> "AL"; }; }
    /** Encodes a nullable numeric value and its separate presence flag. */
    private static String field(Number number, int width, int scale) {
        long value = number == null ? 0 : new BigDecimal(number.toString()).movePointRight(scale).longValueExact();
        if (value < 0) throw new IllegalArgumentException("Negative unsigned field");
        return (number == null ? "N" : "Y") + String.format(Locale.ROOT,"%0" + width + "d",value);
    }
}

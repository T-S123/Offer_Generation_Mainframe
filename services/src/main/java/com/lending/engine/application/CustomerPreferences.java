package com.lending.engine.application;

import com.lending.engine.domain.Model.CustomerInput;
import com.lending.engine.infrastructure.SqlStore;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.hash;

/** Assigns modeled fee preferences using a versioned, one-dimensional adaptation of the POC. */
public final class CustomerPreferences implements AutoCloseable {
    public static final String VERSION = "FEE-KMEANS-1";
    public enum Label { LOWER, MODERATE, HIGHER, INSUFFICIENT_DATA }
    private static final double[] CENTERS = fit();
    private final java.util.concurrent.ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();

    public CustomerPreferences(SqlStore store) {
        worker.scheduleWithFixedDelay(() -> {
            try { store.assignPreferences(250); }
            catch (RuntimeException e) {
                System.err.println("Customer preference assignment deferred: " + e.getClass().getSimpleName());
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    /** Assigns the current profile to its nearest fixed centroid or records insufficient inputs. */
    public static Label classify(CustomerInput profile) {
        double value = estimate(profile);
        if (!Double.isFinite(value)) return Label.INSUFFICIENT_DATA;
        return Label.values()[nearest(value, CENTERS)];
    }

    /** Adapts the POC's deterministic fee-sensitivity hypothesis using its four financial features. */
    private static double estimate(CustomerInput profile) {
        var income = profile.monthlyIncomeUsd();
        var debt = profile.monthlyDebtPaymentsUsd();
        var utilization = profile.creditUtilizationPct();
        var tenure = profile.bankingTenureMonths();
        if (income == null || income.signum() == 0 || debt == null || utilization == null || tenure == null)
            return Double.NaN;
        var seed = hash(java.util.List.of(VERSION, income.doubleValue() * 12,
            Math.round(debt.doubleValue() * 100_000 / income.doubleValue()),
            utilization.doubleValue(), tenure));
        var random = new Random(Long.parseUnsignedLong(seed.substring(0, 16), 16));
        boolean balanceCarrier = random.nextDouble() < .15 + .7 * utilization.doubleValue() / 100;
        boolean feeConscious = !balanceCarrier && random.nextInt(3) == 0;
        return (feeConscious ? 1.3 : .7) + .6 * random.nextDouble();
    }

    /** Fits three ordered K-means centroids on reproducible calibration profiles. */
    private static double[] fit() {
        var reference = Generator.generate(2400, 20261115).stream()
            .mapToDouble(CustomerPreferences::estimate).filter(Double::isFinite).toArray();
        double[] centers = {.8, 1.15, 1.65};
        for (int iteration = 0; iteration < 40; iteration++) {
            var sums = new double[centers.length];
            var counts = new int[centers.length];
            for (double value : reference) {
                int cluster = nearest(value, centers);
                sums[cluster] += value;
                counts[cluster]++;
            }
            for (int i = 0; i < centers.length; i++)
                if (counts[i] != 0) centers[i] = sums[i] / counts[i];
        }
        java.util.Arrays.sort(centers);
        return centers;
    }

    private static int nearest(double value, double[] centers) {
        int index = 0;
        for (int i = 1; i < centers.length; i++)
            if (Math.abs(value - centers[i]) < Math.abs(value - centers[index])) index = i;
        return index;
    }

    @Override public void close() {
        worker.shutdown();
        try { worker.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

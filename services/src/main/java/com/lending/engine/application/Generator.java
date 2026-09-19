/** Reproducible synthetic inputs with financial extremes and missing data, never preset decisions. */
package com.lending.engine.application;

import com.lending.engine.domain.Model.CustomerInput;
import java.math.BigDecimal;
import java.util.*;

/** Reproducible synthetic inputs with financial extremes and missing data, never preset decisions. */
public final class Generator {
    /** Prevents instantiation of this utility-only type. */
    private Generator() {}
    /**
     * Creates reproducible synthetic customer inputs with mixed financial values, preferences and
     * missing-data cases.
     */
    public static List<CustomerInput> generate(int count, long seed) {
        Random r = new Random(seed);
        List<CustomerInput> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int income = 1800 + r.nextInt(12001);
            int debt = (int)(income * (0.05 + r.nextDouble() * 0.60));
            Integer score = 560 + r.nextInt(281);
            BigDecimal incomeValue = money(income);
            Boolean optOut = r.nextInt(10) == 0;

            if (i % 20 == 0) { incomeValue = money(7000); debt = 500; score = 760; optOut = false; }
            if (i % 20 == 1) score = 420;
            if (i % 20 == 2) incomeValue = null;
            if (i % 20 == 3) optOut = true;
            if (i % 20 == 4) incomeValue = BigDecimal.ZERO;
            int vehicleValue = 10000 + r.nextInt(45001);
            result.add(new CustomerInput(null, String.format(Locale.ROOT, "Synthetic customer %05d", i + 1),
                incomeValue, money(debt), score, i % 20 == 0 ? 0 : r.nextInt(4),
                money(i % 20 == 0 ? 20 : r.nextInt(101)), money(i % 20 == 0 ? 10000 : 2000 + r.nextInt(49001)),
                money(i % 20 == 0 ? 3000 : 500 + r.nextInt(20001)), money(i % 20 == 0 ? 15000 : 5000 + r.nextInt(60001)),
                money(i % 20 == 0 ? 20000 : vehicleValue), i % 20 == 0 ? 3 : r.nextInt(18),
                r.nextInt(241), money(r.nextInt(100001)), money(r.nextInt(6001)),
                List.of(r.nextBoolean() ? "CHECKING" : "SAVINGS"), r.nextBoolean(), optOut));
        }
        return result;
    }
    /** Converts a generated monetary amount to a two-decimal USD value. */
    private static BigDecimal money(int amount) { return BigDecimal.valueOf(amount).setScale(2); }
}

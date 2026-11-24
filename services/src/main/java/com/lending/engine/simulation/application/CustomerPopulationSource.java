package com.lending.engine.simulation.application;

import com.lending.engine.application.CustomerPreferences;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.domain.Model.CustomerInput;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.domain.Model.Product;
import com.lending.engine.infrastructure.SqlStore;
import com.lending.engine.simulation.domain.Simulation.*;
import com.lending.engine.storage.StorageClient;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.hash;

/** Freezes bank-owned financial inputs and saved bureau facts without copying customer identities. */
public final class CustomerPopulationSource {
    private final SqlStore customers;
    private final Function<Product,Set<String>> selections;
    private final Function<List<String>,List<BureauProfile>> bureau;
    private final Clock clock;
    private static final Facts MISSING = new Facts(FileStatus.NO_HIT,null,null,null,null,null,null,null,null);

    public CustomerPopulationSource(SqlStore customers, Function<Product,Set<String>> selections,
            Function<List<String>,List<BureauProfile>> bureau, Clock clock) {
        this.customers = customers;
        this.selections = selections;
        this.bureau = bureau;
        this.clock = clock;
    }

    /** Reads prior selections from the selected bank's offer owner in bounded pages. */
    public static Set<String> selectedCustomers(StorageClient client, Product product) {
        Set<String> ids = new HashSet<>();
        String after = "";
        while (true) {
            var page = client.get("selected-customers?product=" + product.name() + "&after=" + after);
            if (!page.isArray()) throw new Problem(503,"Customer selection history is unavailable.");
            for (var id : page) {
                String next = id.asText();
                if (!next.matches("[0-9a-f-]{36}") || next.compareTo(after) <= 0)
                    throw new Problem(503,"Customer selection history could not be verified.");
                ids.add(next);
                after = next;
            }
            if (page.size() < 500) return ids;
        }
    }

    /** Captures a disjoint discovery set and final validation set from one filtered bank population. */
    public CustomerPair freeze(CustomerFilter filter) {
        if (filter == null) throw new Problem(422,"A customer population is required.");
        Instant time = clock.instant();
        var selected = filter.selectedProduct() == null ? null : selections.apply(filter.selectedProduct());
        var rows = new ArrayList<>(customers.experimentCustomers(selected,filter.feeSensitivity(),
            time.atZone(ZoneOffset.UTC).toLocalDate()));
        int count = rows.size();
        if (count < 120 || count > 20_000)
            throw new Problem(422,"Select a group with 120–20,000 matching customer profiles. The current group contains " + count + ".");
        rows.sort(Comparator.comparing(row -> hash("validation-partition:" + row.id())));
        Map<String,Facts> reports = new HashMap<>();
        for (int from = 0; from < count; from += 500) {
            var batch = rows.subList(from,Math.min(from + 500,count)).stream().map(SqlStore.ExperimentCustomer::id).toList();
            Set<String> allowed = new HashSet<>(batch);
            for (var report : bureau.apply(batch)) {
                if (!allowed.contains(report.customerId())) throw new Problem(503,"Credit report correlation failed.");
                if (report.facts().reportedAt() == null || !Instant.parse(report.facts().reportedAt()).isAfter(time))
                    reports.put(report.customerId(),report.facts());
            }
        }
        String snapshot = UUID.randomUUID().toString();
        var people = rows.stream().map(row -> new Person(hash(snapshot + ":" + row.id()),
            minimize(row.data()),reports.getOrDefault(row.id(),MISSING),row.suppressed())).toList();
        int validationCount = Math.max(60,Math.max(count / 5,count - 10_000));
        long seed = Long.parseUnsignedLong(hash(snapshot).substring(0,12),16);
        return new CustomerPair(snapshot,
            partition(people.subList(validationCount,count),seed,time),
            partition(people.subList(0,validationCount),seed+1,time));
    }

    private static Population partition(List<Person> people,long seed,Instant time) {
        return new Population(UUID.randomUUID().toString(),people.size(),seed,time.toString(),"BANK-CUSTOMERS-1",List.copyOf(people));
    }

    /** Retains only financial, consent and product inputs used by the existing simulation. */
    private static CustomerInput minimize(CustomerInput d) {
        return new CustomerInput(null,"Customer profile",d.monthlyIncomeUsd(),d.monthlyDebtPaymentsUsd(),
            d.creditScore(),d.delinquencies12m(),d.creditUtilizationPct(),d.personalLoanAmountUsd(),
            d.requestedCardLimitUsd(),d.autoLoanAmountUsd(),d.vehicleValueUsd(),d.vehicleAgeYears(),
            d.bankingTenureMonths(),d.depositBalanceUsd(),d.monthlySpendUsd(),d.existingProducts(),
            d.marketingOptIn(),d.prescreenOptOut());
    }
}

/** Presentation adapters preserve unknown values and backend comparison semantics. */
import type { Data } from "./api";
export const customerNumbers = [
  "monthlyIncomeUsd",
  "monthlyDebtPaymentsUsd",
  "creditScore",
  "delinquencies12m",
  "creditUtilizationPct",
  "personalLoanAmountUsd",
  "requestedCardLimitUsd",
  "autoLoanAmountUsd",
  "vehicleValueUsd",
  "vehicleAgeYears",
  "bankingTenureMonths",
  "depositBalanceUsd",
  "monthlySpendUsd",
];
export function customerPayload(fields: Data): Data {
  const result: Data = {
    displayName: String(fields.displayName || "").trim(),
    externalReference: String(fields.externalReference || "").trim() || null,
  };
  for (const field of customerNumbers)
    result[field] =
      fields[field] === "" || fields[field] == null
        ? null
        : Number(fields[field]);
  for (const field of ["marketingOptIn", "prescreenOptOut"])
    result[field] =
      fields[field] === "" || fields[field] == null
        ? null
        : fields[field] === true || fields[field] === "true";
  result.existingProducts = fields.existingProducts ?? [];
  result.preferredChannels = fields.preferredChannels ?? null;
  return result;
}
export function latestProfile(customer: Data) {
  return customer.profiles?.[customer.profiles.length - 1];
}
export const human = (text: unknown) =>
  String(text ?? "")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replaceAll("_", " ")
    .toLowerCase()
    .replace(/^./, (c) => c.toUpperCase());
export const pct = (value: unknown) =>
  typeof value === "number" && Number.isFinite(value)
    ? value.toFixed(1) + "%"
    : "—";
export const number = (value: unknown) =>
  typeof value === "number" && Number.isFinite(value)
    ? new Intl.NumberFormat("en-US", { maximumFractionDigits: 1 }).format(value)
    : "—";
export const money = (value: unknown) =>
  typeof value === "number" && Number.isFinite(value)
    ? new Intl.NumberFormat("en-US", {
        style: "currency",
        currency: "USD",
        maximumFractionDigits: 2,
      }).format(value)
    : "—";
export const date = (value: unknown) =>
  typeof value === "string" && !isNaN(Date.parse(value))
    ? new Date(value).toLocaleString([], {
        dateStyle: "medium",
        timeStyle: "short",
      })
    : "—";
export function performanceView(value: Data) {
  const c = value.comparison ?? {},
    observed = c.observed ?? {},
    prediction = c.prediction ?? {};
  return {
    prediction,
    observed,
    expected: prediction.estimatePct,
    actual: observed.acceptanceAmongEligiblePct,
    status: c.comparability ?? "UNAVAILABLE",
    gap:
      c.comparability === "MATCH" && typeof c.gapPercentagePoints === "number"
        ? c.gapPercentagePoints
        : null,
    unavailable: observed.acceptanceAmongEligiblePct == null,
    stale: value.sourceRefresh?.status === "SOURCE_UNAVAILABLE",
  };
}
export const offerFields = [
  "name",
  "active",
  "startsOn",
  "endsOn",
  "minimumAmountUsd",
  "maximumAmountUsd",
  "illustrativeAprPct",
  "annualFeeUsd",
  "termMonths",
];
export const stageFields = [
  "minimumScore",
  "minimumIncomeUsd",
  "maximumDtiPct",
  "maximumUtilizationPct",
  "maximumDelinquencies",
  "maximumAmountUsd",
  "incomeMultiple",
  "maximumLtvPct",
  "maximumVehicleAge",
  "minimumTenureMonths",
  "excludeExistingProduct",
];
export const bureauFields = [
  "minimumScore",
  "minimumIncomeUsd",
  "maximumDtiPct",
  "maximumUtilizationPct",
  "maximumDelinquencies",
  "maximumAmountUsd",
  "maximumLtvPct",
  "maximumInquiries",
  "minimumHistoryMonths",
  "maximumReportAgeDays",
  "allowBankruptcy",
];
export const labels: Record<string, string> = {
  displayName: "Full name",
  externalReference: "Customer reference (optional)",
  monthlyIncomeUsd: "Monthly income ($)",
  monthlyDebtPaymentsUsd: "Monthly debt payments ($)",
  creditScore: "Self-reported credit score",
  delinquencies12m: "Delinquencies in the last 12 months",
  creditUtilizationPct: "Credit utilization (%)",
  personalLoanAmountUsd: "Personal loan amount ($)",
  requestedCardLimitUsd: "Requested card limit ($)",
  autoLoanAmountUsd: "Auto loan amount ($)",
  vehicleValueUsd: "Vehicle value ($)",
  vehicleAgeYears: "Vehicle age (years)",
  bankingTenureMonths: "Banking tenure (months)",
  depositBalanceUsd: "Deposit balance ($)",
  monthlySpendUsd: "Monthly spending ($)",
  marketingOptIn: "Receive marketing offers",
  prescreenOptOut: "Opt out of prescreening",
  name: "Offer name",
  startsOn: "Start date",
  endsOn: "End date",
  minimumAmountUsd: "Minimum amount ($)",
  maximumAmountUsd: "Maximum amount ($)",
  illustrativeAprPct: "APR (%)",
  annualFeeUsd: "Annual fee ($)",
  termMonths: "Term (months; cards use 0)",
  minimumScore: "Minimum credit score (0 disables)",
  minimumIncomeUsd: "Minimum monthly income ($)",
  maximumDtiPct: "Maximum debt-to-income (%)",
  maximumUtilizationPct: "Maximum utilization (%)",
  maximumDelinquencies: "Maximum delinquencies",
  incomeMultiple: "Maximum income multiple",
  maximumLtvPct: "Maximum loan-to-value (%)",
  maximumVehicleAge: "Maximum vehicle age",
  minimumTenureMonths: "Minimum banking tenure (months)",
  excludeExistingProduct: "Exclude a product already held",
  maximumInquiries: "Maximum inquiries",
  minimumHistoryMonths: "Minimum credit history (months)",
  maximumReportAgeDays: "Maximum report age (days)",
  allowBankruptcy: "Allow bankruptcy",
};

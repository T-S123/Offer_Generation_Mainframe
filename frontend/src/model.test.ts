import { describe, it, expect } from "vitest";
import { customerPayload, latestProfile, performanceView, pct } from "./model";
describe("customer API payload", () => {
  it("preserves unknown facts, explicit zero, false consent, and omitted contact preferences", () => {
    const value = customerPayload({
      displayName: " Ada ",
      monthlyIncomeUsd: "",
      monthlyDebtPaymentsUsd: "0",
      marketingOptIn: "false",
      prescreenOptOut: "true",
    });
    expect(value.displayName).toBe("Ada");
    expect(value.monthlyIncomeUsd).toBeNull();
    expect(value.monthlyDebtPaymentsUsd).toBe(0);
    expect(value.marketingOptIn).toBe(false);
    expect(value.prescreenOptOut).toBe(true);
    expect(value.preferredChannels).toBeNull();
  });
  it("prefills the latest saved revision and preserves its facts on update", () => {
    const current = {
      version: 2,
      data: {
        displayName: "Ada",
        creditScore: 735,
        monthlyIncomeUsd: 8500,
        existingProducts: ["SAVINGS"],
        preferredChannels: [],
      },
    };
    const profile = latestProfile({
      profiles: [{ version: 1, data: { displayName: "Old" } }, current],
    });
    expect(profile).toBe(current);
    const value = customerPayload(profile.data);
    expect(value.creditScore).toBe(735);
    expect(value.preferredChannels).toEqual([]);
  });
});
describe("recorded performance", () => {
  it("shows the prediction and observation but never invents a gap across incompatible targets", () => {
    const view = performanceView({
      comparison: {
        prediction: { estimatePct: 80 },
        observed: { acceptanceAmongEligiblePct: 40, E: 100, Y: 40 },
        comparability: "TARGET_MISMATCH",
        gapPercentagePoints: -40,
      },
    });
    expect(view.expected).toBe(80);
    expect(view.actual).toBe(40);
    expect(view.gap).toBeNull();
  });
  it("uses the authoritative matched gap without calculating one", () => {
    expect(
      performanceView({
        comparison: { comparability: "MATCH", gapPercentagePoints: -12.3 },
      }).gap,
    ).toBe(-12.3);
    expect(
      performanceView({
        comparison: {
          comparability: "MATCH",
          prediction: { estimatePct: 80 },
          observed: { acceptanceAmongEligiblePct: 40 },
        },
      }).gap,
    ).toBeNull();
  });
  it("distinguishes unavailable outcomes from recorded zero and marks stale snapshots", () => {
    expect(pct(null)).toBe("—");
    expect(pct(0)).toBe("0.0%");
    expect(
      performanceView({
        comparison: { observed: { acceptanceAmongEligiblePct: null } },
      }).unavailable,
    ).toBe(true);
    const v = performanceView({
      comparison: { observed: { acceptanceAmongEligiblePct: 0 } },
      sourceRefresh: { status: "SOURCE_UNAVAILABLE" },
    });
    expect(v.unavailable).toBe(false);
    expect(v.stale).toBe(true);
  });
});

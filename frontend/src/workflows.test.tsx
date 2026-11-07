// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { AiWorkspace } from "./Ai";
import { Dashboard } from "./Dashboard";
const request = vi.hoisted(() => vi.fn());
vi.mock("./api", async () => ({
  ...(await vi.importActual("./api")),
  api: request,
}));
beforeEach(() => {
  request.mockReset();
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
});
afterEach(cleanup);
const usage = {
  evaluations: 1,
  modelCalls: 1,
  reservedTokens: 100,
  reservedCostUsd: 0.01,
  limits: {
    maxEvaluations: 10,
    maxModelCalls: 10,
    maxTokens: 1000,
    maxCostUsd: 1,
  },
};
const props = {
  draftId: "",
  publicationId: "",
  onPolicies: vi.fn(),
  onPublished: vi.fn(),
};
function mountWorkflow(
  read: () => any,
  command?: (path: string, body: any) => any,
) {
  request.mockImplementation(
    async (path: string, method = "GET", body?: any) => {
      if (method !== "GET") return command?.(path, body) ?? {};
      if (path.startsWith("ai/workflows?"))
        return [
          {
            id: "wf",
            version: 1,
            data: { state: "NEEDS_INPUT", intent: "Test objective" },
          },
        ];
      if (path === "ai/workflows/wf") return structuredClone(read());
      return [];
    },
  );
  render(<AiWorkspace {...props} />);
}
describe("A2A interaction boundaries", () => {
  it("preserves a multiline answer through refresh and sends the latest version with its question ID", async () => {
    let version = 3;
    const workflow = {
      state: "NEEDS_INPUT",
      intent: "Test objective",
      pendingQuestion: {
        id: "question-1",
        agent: "ANALYZER",
        question: {
          topic: "TRADEOFF",
          prompt: "Which constraint takes priority?",
          why: "This affects the eligible group.",
          choices: ["Preserve eligibility", "Reduce cost"],
        },
      },
      steps: {},
      evaluations: [],
    };
    mountWorkflow(() => ({ id: "wf", version, workflow, usage }));
    fireEvent.click(
      await screen.findByRole("button", { name: /Test objective/ }),
    );
    const answer = await screen.findByLabelText("Your answer");
    fireEvent.change(answer, {
      target: {
        value: "Preserve eligibility.\nKeep the existing risk limits.",
      },
    });
    version = 7;
    fireEvent.click(
      screen.getByRole("button", { name: "Refresh" }),
    );
    await waitFor(() =>
      expect(
        request.mock.calls.filter((c) => c[0] === "ai/workflows/wf").length,
      ).toBeGreaterThan(1),
    );
    expect((answer as HTMLTextAreaElement).value).toContain("\nKeep");
    fireEvent.click(
      screen.getByRole("button", { name: /Send answer and continue/ }),
    );
    await waitFor(() =>
      expect(request).toHaveBeenCalledWith("ai/workflows/wf/messages", "POST", {
        expectedVersion: 7,
        questionId: "question-1",
        text: "Preserve eligibility.\nKeep the existing risk limits.",
      }),
    );
  });
  it("prepares a preview first and publishes only the separately confirmed exact preview hash", async () => {
    const workflow = {
      state: "COMPLETED",
      intent: "Test objective",
      evaluations: [
        {
          candidateId: "candidate-1",
          phase: "VALIDATION",
          status: "COMPLETED",
          runId: "run-1",
        },
      ],
      steps: {},
    };
    mountWorkflow(
      () => ({ id: "wf", version: 9, workflow, usage }),
      (path) =>
        path.endsWith("/publication-previews")
          ? {
              previewId: "preview-1",
              previewHash: "exact-reviewed-hash",
              configuration: {
                offer: { name: "Reviewed offer", illustrativeAprPct: 9 },
              },
              changedPaths: ["offer.illustrativeAprPct"],
              prediction: { estimatePct: 72 },
              expiresAt: "2099-01-01T00:00:00Z",
            }
          : { receipt: { offerId: "published-1" } },
    );
    fireEvent.click(
      await screen.findByRole("button", { name: /Test objective/ }),
    );
    fireEvent.change(
      await screen.findByLabelText("Final-validation candidate"),
      { target: { value: "candidate-1" } },
    );
    fireEvent.change(screen.getByLabelText("Review note"), {
      target: { value: "Reviewed the final validation evidence." },
    });
    fireEvent.click(
      screen.getByRole("button", { name: /Prepare publication preview/ }),
    );
    const publish = await screen.findByRole("button", {
      name: "Confirm publication",
    });
    expect((publish as HTMLButtonElement).disabled).toBe(true);
    expect(request.mock.calls.some((c) => c[0].endsWith("/confirm"))).toBe(
      false,
    );
    fireEvent.click(screen.getByLabelText(/I reviewed this configuration/));
    fireEvent.click(publish);
    await waitFor(() =>
      expect(request).toHaveBeenCalledWith(
        "ai/publication-previews/preview-1/confirm",
        "POST",
        { previewHash: "exact-reviewed-hash", confirm: true },
      ),
    );
    await screen.findByText("Published to catalog", { exact: true });
  });
  it("starts a fresh experiment when the generic action follows a publication investigation", async () => {
    request.mockResolvedValue([]);
    render(<AiWorkspace {...props} publicationId="prior-publication" />);
    await screen.findByRole("heading", {
      name: "Investigate recorded performance",
    });
    fireEvent.click(screen.getByRole("button", { name: "Close dialog" }));
    fireEvent.click(
      screen.getByRole("button", { name: /Start A2A exploration/ }),
    );
    await screen.findByRole("heading", { name: "Start an A2A experiment" });
    expect(
      screen.queryByRole("heading", {
        name: "Investigate recorded performance",
      }),
    ).toBeNull();
  });
});
describe("recorded dashboard presentation", () => {
  it("renders recorded counts and incompatible prediction labels without inventing a forecast gap", async () => {
    request.mockImplementation(async (path: string) => {
      if (path === "ai/performance/publications")
        return [
          {
            id: "published",
            data: {
              prediction: { estimatePct: 80 },
              preview: {
                configuration: {
                  offer: { name: "Test publication", product: "PERSONAL_LOAN" },
                },
              },
            },
          },
        ];
      if (path.includes("performance/publications/"))
        return {
          comparison: {
            prediction: { estimatePct: 80, scope: "CATALOG_TERMS_ONLY" },
            observed: {
              acceptanceAmongEligiblePct: 40,
              E: 10,
              Y: 4,
              matureEnrollments: 10,
              provisionalEnrollments: 0,
              maturity: "MATURE",
            },
            comparability: "TARGET_MISMATCH",
            gapPercentagePoints: null,
          },
        };
      return [];
    });
    render(
      <Dashboard onInvestigate={vi.fn()} onRun={vi.fn()} onNew={vi.fn()} />,
    );
    await screen.findByText("40.0%");
    expect(screen.getByText("80.0%")).toBeTruthy();
    expect(screen.getByText("4 selected / 10 eligible")).toBeTruthy();
    expect(screen.getByText("Target mismatch")).toBeTruthy();
    expect(screen.queryByText(/Validated gap:/)).toBeNull();
  });
});

/** Network selection changes the server-side query; the client never filters a mixed history itself. */
describe("A2A network history", () => {
  it("keeps simulation and feedback history separate and defaults publication entry to feedback", async () => {
    request.mockResolvedValue([]);
    const view = render(<AiWorkspace {...props} />);
    await waitFor(() => expect(request).toHaveBeenCalledWith("ai/workflows?limit=20&network=SIMULATION"));
    fireEvent.change(screen.getByLabelText("A2A network"), {target: {value: "FEEDBACK"}});
    await waitFor(() => expect(request).toHaveBeenCalledWith("ai/workflows?limit=20&network=FEEDBACK"));
    await screen.findByText("No saved investigations");
    view.unmount();
    render(<AiWorkspace {...props} publicationId="published" />);
    expect((screen.getByLabelText("A2A network") as HTMLSelectElement).value).toBe("FEEDBACK");
  });
});

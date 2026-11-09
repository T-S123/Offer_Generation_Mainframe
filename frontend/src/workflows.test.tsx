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

describe("new experiment phase allocation", () => {
  const defaults = {maxEvaluations:200,maxModelCalls:60,maxTokens:500000,maxCostUsd:25,
    exploration:119,refinement:60,validation:20,minimumEligible:30,eligibilityFloor:0,
    acceptanceFloor:0,deadline:"2099-01-01T00:00:00Z"};
  async function openLaunch(recommend?: (path:string)=>Promise<any>) {
    request.mockImplementation(async (path:string,method="GET",body?:any) => {
      if(path==="ai/defaults")return {...defaults};
      if(path.startsWith("ai/defaults?"))return recommend ? recommend(path) : {...defaults,maxEvaluations:20,exploration:12,refinement:5,validation:2};
      if(path==="business/drafts")return [{id:"draft",name:"A2A filming personal loan",version:2}];
      if(path==="business/populations")return [{id:"discovery",seed:20261105,count:10000},{id:"validation",seed:20261104,count:10000}];
      if(path==="ai/workflows"&&method==="POST")return {id:"created"};
      if(path==="ai/workflows/created")return {id:"created",version:1,workflow:{state:"PLANNING",steps:{}},usage};
      return [];
    });
    render(<AiWorkspace {...props}/>);
    fireEvent.click(await screen.findByRole("button",{name:/Start A2A exploration/}));
    await screen.findByLabelText("Total simulation limit",{exact:false});
    fireEvent.change(screen.getByLabelText("Saved offer draft",{exact:false}),{target:{value:"draft"}});
    fireEvent.change(screen.getByLabelText("Discovery population",{exact:false}),{target:{value:"discovery"}});
    fireEvent.change(screen.getByLabelText("Final-validation population",{exact:false}),{target:{value:"validation"}});
    fireEvent.change(screen.getByLabelText("Objective and constraints",{exact:false}),{target:{value:"Explore bureau score 680 through 740."}});
    fireEvent.click(screen.getByLabelText(/I approve model execution/));
  }
  it("changing 200 to 20 obtains 12/5/2 from the API, preserves other edits and submits a valid manual override",async()=>{
    await openLaunch();
    fireEvent.change(screen.getByLabelText("Maximum cost ($)",{exact:false}),{target:{value:"10"}});
    fireEvent.change(screen.getByLabelText("Minimum eligible customers",{exact:false}),{target:{value:"5"}});
    fireEvent.change(screen.getByLabelText("Total simulation limit",{exact:false}),{target:{value:"20"}});
    await waitFor(()=>expect((screen.getByLabelText("Exploration trials",{exact:false}) as HTMLInputElement).value).toBe("12"));
    expect((screen.getByLabelText("Refinement trials",{exact:false}) as HTMLInputElement).value).toBe("5");
    expect((screen.getByLabelText("Validation trials",{exact:false}) as HTMLInputElement).value).toBe("2");
    expect((screen.getByLabelText("Maximum cost ($)",{exact:false}) as HTMLInputElement).value).toBe("10");
    expect((screen.getByLabelText("Minimum eligible customers",{exact:false}) as HTMLInputElement).value).toBe("5");
    fireEvent.change(screen.getByLabelText("Exploration trials",{exact:false}),{target:{value:"11"}});
    fireEvent.change(screen.getByLabelText("Refinement trials",{exact:false}),{target:{value:"6"}});
    fireEvent.click(screen.getByRole("button",{name:"Start A2A workflow"}));
    await waitFor(()=>expect(request).toHaveBeenCalledWith("ai/workflows","POST",expect.objectContaining({
      budget:expect.objectContaining({maxEvaluations:20,exploration:11,refinement:6,validation:2,maxCostUsd:10,minimumEligible:5})
    })));
  });
  it("prevents submission when manual phase edits exceed the total or omit validation",async()=>{
    await openLaunch();
    fireEvent.change(screen.getByLabelText("Total simulation limit",{exact:false}),{target:{value:"20"}});
    await waitFor(()=>expect((screen.getByLabelText("Validation trials",{exact:false}) as HTMLInputElement).value).toBe("2"));
    fireEvent.change(screen.getByLabelText("Exploration trials",{exact:false}),{target:{value:"13"}});
    expect(screen.getByText(/Phase allocations need 21 evaluations/)).toBeTruthy();
    const start=screen.getByRole("button",{name:"Start A2A workflow"}) as HTMLButtonElement;
    expect(start.disabled).toBe(true);
    fireEvent.submit(start.closest("form")!);
    expect(request.mock.calls.some(c=>c[0]==="ai/workflows"&&c[1]==="POST")).toBe(false);
    fireEvent.change(screen.getByLabelText("Exploration trials",{exact:false}),{target:{value:"12"}});
    expect(start.disabled).toBe(false);
    fireEvent.change(screen.getByLabelText("Validation trials",{exact:false}),{target:{value:"1"}});
    expect(start.disabled).toBe(true);
    expect(screen.getByText(/Use at least 1 exploration trial/)).toBeTruthy();
  });
  it("ignores late recommendations after another total or a manual phase edit",async()=>{
    const pending=new Map<string,(v:any)=>void>();
    await openLaunch(path=>new Promise(resolve=>pending.set(path,resolve)));
    fireEvent.change(screen.getByLabelText("Total simulation limit",{exact:false}),{target:{value:"30"}});
    fireEvent.change(screen.getByLabelText("Total simulation limit",{exact:false}),{target:{value:"20"}});
    pending.get("ai/defaults?maxEvaluations=20")!({...defaults,exploration:12,refinement:5,validation:2});
    await waitFor(()=>expect((screen.getByLabelText("Exploration trials",{exact:false}) as HTMLInputElement).value).toBe("12"));
    pending.get("ai/defaults?maxEvaluations=30")!({...defaults,exploration:18,refinement:8,validation:3});
    await waitFor(()=>expect(screen.queryByText("Balancing phase allocations…")).toBeNull());
    expect((screen.getByLabelText("Exploration trials",{exact:false}) as HTMLInputElement).value).toBe("12");
    fireEvent.change(screen.getByLabelText("Total simulation limit",{exact:false}),{target:{value:"25"}});
    fireEvent.change(screen.getByLabelText("Exploration trials",{exact:false}),{target:{value:"14"}});
    pending.get("ai/defaults?maxEvaluations=25")!({...defaults,exploration:15,refinement:7,validation:2});
    await waitFor(()=>expect(screen.queryByText("Balancing phase allocations…")).toBeNull());
    expect((screen.getByLabelText("Exploration trials",{exact:false}) as HTMLInputElement).value).toBe("14");
  });
});


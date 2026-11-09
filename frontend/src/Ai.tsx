import { useEffect, useRef, useState, type FormEvent } from "react";
import { InvestigationAnswer } from "./InvestigationAnswer";
import { api, errorText, id, key, type Data } from "./api";
import { date, human, money, number, pct } from "./model";
import {
  Badge,
  Empty,
  Evidence,
  Field,
  Icon,
  Loading,
  Modal,
  Notice,
  PageTitle,
  Progress,
  useRemote,
} from "./ui";

/** Uses plain headings and empty states for separate simulation and investigation history; owner APIs retain questions, budgets, trials and publication authority. */
export function AiWorkspace({
  draftId,
  publicationId,
  onPolicies,
  onPublished,
}: {
  draftId: string;
  publicationId: string;
  onPolicies: () => void;
  onPublished: () => void;
}) {
  const [investigation, setInvestigation] = useState(publicationId);
  const [network, setNetwork] = useState(publicationId ? "FEEDBACK" : "SIMULATION");
  const [selected, setSelected] = useState(""),
    [launch, setLaunch] = useState(Boolean(draftId || publicationId)),
    [after, setAfter] = useState(""),
    [history, setHistory] = useState<string[]>([]);
  const workflows = useRemote<Data[]>(
    "ai/workflows?limit=20&network=" + network + (after ? "&after=" + id(after) : ""),
  );
  if (selected)
    return (
      <Workflow
        key={selected}
        workflowId={selected}
        onBack={() => {
          setSelected("");
          workflows.refresh();
        }}
        onPublished={onPublished}
      />
    );
  return (
    <>
      <PageTitle
        eyebrow="Agent collaboration"
        title={network === "SIMULATION" ? "A2A simulations" : "Performance investigations"}
        action={
          <button
            className="button primary"
            onClick={() => {
              setInvestigation("");
              setNetwork("SIMULATION");
              setAfter("");
              setHistory([]);
              setLaunch(true);
            }}
          >
            Start A2A exploration <Icon name="spark" />
          </button>
        }
      />
      <div className="ai-intro panel">
        <div>
          <Badge>A2A protocol</Badge>
          <h2>{network === "SIMULATION" ? "Simulation network" : "Investigation network"}</h2>
          {network === "FEEDBACK" && <p>Start an investigation from a published offer on the performance dashboard.</p>}
        </div>
        <button className="button secondary" onClick={onPolicies}>
          Review policy evidence <Icon name="book" />
        </button>
      </div>
      <Notice>{workflows.error}</Notice>
      {workflows.error && (
        <Notice tone="info">
          Start the AI services with scripts/run-ai.ps1 to use A2A. Manual
          experiments remain available in Simulations.
        </Notice>
      )}
      <section className="panel">
        <div className="panel-top">
          <h2>{network === "SIMULATION" ? "Saved simulations" : "Saved investigations"}</h2>
          <label>
            A2A network
            <select value={network} onChange={(e) => {
              setNetwork(e.target.value);
              setAfter("");
              setHistory([]);
            }}>
              <option value="SIMULATION">Simulation and offer design</option>
              <option value="FEEDBACK">Performance investigations</option>
            </select>
          </label>
          <button className="text-button" onClick={workflows.refresh}>
            <Icon name="refresh" />
            Refresh
          </button>
        </div>
        {workflows.data?.length ? (
          <div className="workflow-list">
            {workflows.data.map((w) => (
              <button key={w.id} onClick={() => setSelected(w.id)}>
                <span className="entry-symbol">
                  <Icon name="spark" />
                </span>
                <span>
                  <b>{w.data.intent}</b>
                  <small>{date(w.data.createdAt)}</small>
                </span>
                <Badge>{human(w.data.state)}</Badge>
                <Icon />
              </button>
            ))}
          </div>
        ) : (
          <Empty
            title={
              workflows.loading
                ? "Loading explorations…"
                : network === "FEEDBACK" ? "No saved investigations" : "No saved A2A simulations"
            }
          >
            {network === "FEEDBACK"
              ? "Open a published offer on the performance dashboard to start an investigation."
              : "Start with a saved draft and two independent populations. Review the budget before any model work begins."}
          </Empty>
        )}
        <div className="pagination">
          <button
            className="button secondary"
            disabled={!history.length}
            onClick={() => {
              setAfter(history.at(-1)!);
              setHistory((h) => h.slice(0, -1));
            }}
          >
            Previous
          </button>
          <button
            className="button secondary"
            disabled={workflows.data?.length !== 20}
            onClick={() => {
              setHistory((h) => [...h, after]);
              setAfter(workflows.data![19].id);
            }}
          >
            Next
          </button>
        </div>
      </section>
      {launch && (
        <Launch
          draftId={draftId}
          publicationId={investigation}
          onClose={() => setLaunch(false)}
          onStarted={(v) => {
            setLaunch(false);
            setNetwork(v.workflow?.network ?? (investigation ? "FEEDBACK" : "SIMULATION"));
            setSelected(v.id);
          }}
        />
      )}
    </>
  );
}
const budgetFields: Record<string, string> = {
  maxEvaluations: "Total simulation limit",
  maxModelCalls: "Model call limit",
  maxTokens: "Token limit",
  maxCostUsd: "Maximum cost ($)",
  exploration: "Exploration trials",
  refinement: "Refinement trials",
  validation: "Validation trials",
  minimumEligible: "Minimum eligible customers",
  eligibilityFloor: "Eligibility floor (%)",
  acceptanceFloor: "Acceptance floor (%)",
};
/** Presents allocation arithmetic; the owner API recommends phases and remains authoritative on admission. */
function phaseAllocationError(value: Data): string {
  const total = value.maxEvaluations;
  if (!Number.isInteger(total) || total < 4 || total > 1000)
    return "Total simulation limit must be an integer from 4 to 1000.";
  const { exploration, refinement, validation } = value;
  if (![exploration, refinement, validation].every(Number.isInteger) ||
      exploration < 1 || refinement < 0 || validation < 2)
    return "Use at least 1 exploration trial, 0 refinement trials and 2 validation trials, plus 1 baseline.";
  const allocated = 1 + exploration + refinement + validation;
  return allocated > total
    ? `Phase allocations need ${allocated} evaluations, including 1 baseline, but the total limit is ${total}. Reduce the phase allocations or increase the total.`
    : "";
}

/** Rebalances new-run totals through the API, preserves manual edits and rejects stale responses; extensions retain their saved phases. */
function BudgetFields({ value, onChange, extension = false, onAllocationStatus }: {
  value: Data;
  onChange: (v: Data) => void;
  extension?: boolean;
  onAllocationStatus?: (valid: boolean) => void;
}) {
  const latest = useRef(value); latest.current = value;
  const sequence = useRef(0);
  const [balancing, setBalancing] = useState(false), [allocationError, setAllocationError] = useState("");
  const invalid = phaseAllocationError(value);
  useEffect(() => { onAllocationStatus?.(!balancing && !allocationError && !invalid); },
    [balancing, allocationError, invalid, onAllocationStatus]);
  useEffect(() => () => { sequence.current++; }, []);
  function change(field: string, raw: string) {
    const next = { ...latest.current, [field]: raw === "" ? "" : Number(raw) };
    latest.current = next; onChange(next);
    if (extension) return;
    if (["exploration", "refinement", "validation"].includes(field)) {
      sequence.current++; setBalancing(false); setAllocationError(""); return;
    }
    if (field !== "maxEvaluations") return;
    const current = ++sequence.current;
    setAllocationError(""); setBalancing(false);
    if (!Number.isInteger(next.maxEvaluations) || next.maxEvaluations < 4 || next.maxEvaluations > 1000) return;
    setBalancing(true);
    api(`ai/defaults?maxEvaluations=${next.maxEvaluations}`).then((suggested: Data) => {
      if (sequence.current !== current) return;
      const updated = { ...latest.current, exploration: suggested.exploration,
        refinement: suggested.refinement, validation: suggested.validation };
      latest.current = updated; onChange(updated);
    }).catch(e => {
      if (sequence.current === current) setAllocationError(errorText(e));
    }).finally(() => { if (sequence.current === current) setBalancing(false); });
  }
  const allocated = 1 + Number(value.exploration) + Number(value.refinement) + Number(value.validation);
  return (
    <details className="budget-settings" open={extension}>
      <summary>Resource budget · up to {money(value.maxCostUsd)} · {number(value.maxEvaluations)} evaluations</summary>
      <p className="footnote">
        {extension ? "Existing phase allocations stay unchanged when extending a run." :
          "Changing the total automatically balances the phases. You can edit the phase values afterward."}
      </p>
      <p role="status">{balancing ? "Balancing phase allocations…" :
        `Allocation: 1 baseline + ${value.exploration} exploration + ${value.refinement} refinement + ${value.validation} validation = ${allocated} of ${value.maxEvaluations} evaluations.`}</p>
      <Notice>{allocationError || (!balancing && invalid)}</Notice>
      <div className="form-grid">
        {Object.entries(budgetFields).map(([field, label]) => (
          <Field key={field} name={field} label={label} type="number" value={value[field]}
            min={field === "maxEvaluations" ? 4 : field === "validation" ? 2 : field === "exploration" || field === "minimumEligible" ? 1 : 0}
            max={field === "maxEvaluations" ? 1000 : undefined}
            step={field === "maxCostUsd" ? 0.01 : 1}
            readOnly={extension && !field.startsWith("max")}
            onChange={(v) => change(field, v)} required />
        ))}
        <Field name="deadline" label="Deadline (UTC ISO timestamp)" value={value.deadline}
          onChange={(v) => onChange({ ...value, deadline: v })} required />
      </div>
    </details>
  );
}
function Launch({
  draftId,
  publicationId,
  onClose,
  onStarted,
}: {
  draftId: string;
  publicationId: string;
  onClose: () => void;
  onStarted: (v: Data) => void;
}) {
  const defaults = useRemote<Data>("ai/defaults"),
    drafts = useRemote<Data[]>("business/drafts"),
    populations = useRemote<Data[]>("business/populations");
  const [allocationValid, setAllocationValid] = useState(true);
  const [draft, setDraft] = useState(draftId),
    [population, setPopulation] = useState(""),
    [validation, setValidation] = useState(""),
    [intent, setIntent] = useState(""),
    [budget, setBudget] = useState<Data>(),
    [locks, setLocks] = useState<string[]>([]),
    [seed, setSeed] = useState(20261021),
    [mode, setMode] = useState("DEMO"),
    [accepted, setAccepted] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const request = useRef({ fingerprint: "", id: "" });
  useEffect(() => {
    if (defaults.data) setBudget((b) => b ?? defaults.data);
  }, [defaults.data]);
  async function start(e: FormEvent) {
    e.preventDefault();
    if (!budget || !accepted || !allocationValid || phaseAllocationError(budget)) return;
    setError("");
    setBusy(true);
    try {
      const payload = publicationId
        ? { text: intent, budget, sourceMode: mode }
        : {
            draftId: draft,
            populationId: population,
            validationPopulationId: validation,
            intent,
            lockedStages: locks,
            budget,
            seed,
            autoExecute: true,
          };
      const fingerprint = JSON.stringify(payload);
      if (request.current.fingerprint !== fingerprint)
        request.current = { fingerprint, id: key() };
      const result = await api(
        publicationId
          ? "ai/publications/" + id(publicationId) + "/investigations"
          : "ai/workflows",
        "POST",
        { requestId: request.current.id, ...payload },
      );
      onStarted(result);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  const discovery = populations.data?.find((p) => p.id === population);
  return (
    <Modal
      title={
        publicationId
          ? "Investigate recorded performance"
          : "Start an A2A experiment"
      }
      onClose={() => !busy && onClose()}
    >
      <form onSubmit={start}>
        <Notice>
          {error || defaults.error || drafts.error || populations.error}
        </Notice>
        {publicationId ? (
          <>
            <p>
              Investigate publication{" "}
              <b className="identifier">{publicationId}</b>. The agents can
              explain recorded evidence; this does not publish a replacement.
            </p>
            <label className="field">
              <span>Observation source</span>
              <select value={mode} onChange={(e) => setMode(e.target.value)}>
                <option value="DEMO">Local demo outcomes</option>
                <option value="OBSERVED_REAL">
                  Configured real-source outcomes
                </option>
              </select>
            </label>
          </>
        ) : (
          <>
            <label className="field">
              <span>Saved offer draft</span>
              <select
                required
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
              >
                <option value="">Select a draft</option>
                {drafts.data?.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.name} · v{d.version}
                  </option>
                ))}
              </select>
            </label>
            <div className="form-grid">
              {[
                [population, setPopulation, "Discovery population"],
                [validation, setValidation, "Final-validation population"],
              ].map(([v, set, label], i) => (
                <label className="field" key={String(label)}>
                  <span>{String(label)}</span>
                  <select
                    required
                    value={v as string}
                    onChange={(e) =>
                      (set as (s: string) => void)(e.target.value)
                    }
                  >
                    <option value="">Select a population</option>
                    {populations.data?.map((p) => (
                      <option
                        disabled={
                          i === 1 &&
                          (p.id === population || p.seed === discovery?.seed)
                        }
                        key={p.id}
                        value={p.id}
                      >
                        {p.count} customers · seed {p.seed} · {p.id.slice(0, 8)}
                      </option>
                    ))}
                  </select>
                </label>
              ))}
            </div>
            <p className="footnote">
              Generate populations in Simulations. Final validation needs a
              fresh population with a different seed; the engine checks prior
              use.
            </p>
            <Field
              name="analysisSeed"
              label="Analysis seed"
              type="number"
              step={1}
              value={seed}
              onChange={(v) => setSeed(Number(v))}
              required
            />
            <fieldset>
              <legend>Keep these stages unchanged</legend>
              <div className="checks">
                {["offer", "underwriting", "marketing", "bureau"].map((s) => (
                  <label className="check" key={s}>
                    <input
                      type="checkbox"
                      checked={locks.includes(s)}
                      onChange={(e) =>
                        setLocks((l) =>
                          e.target.checked
                            ? [...l, s]
                            : l.filter((x) => x !== s),
                        )
                      }
                    />
                    {human(s)}
                  </label>
                ))}
              </div>
            </fieldset>
          </>
        )}
        <label className="field">
          <span>
            {publicationId
              ? "What would you like to understand?"
              : "Objective and constraints"}
          </span>
          <textarea
            required
            maxLength={6000}
            rows={4}
            value={intent}
            onChange={(e) => setIntent(e.target.value)}
            placeholder={
              publicationId
                ? "Explain the recorded acceptance and any limitations in comparing it with the original prediction."
                : "Describe the offer improvement you want, the customer group, and your constraints."
            }
          />
        </label>
        {budget ? (
          <BudgetFields value={budget} onChange={setBudget} onAllocationStatus={setAllocationValid} />
        ) : (
          <Loading />
        )}
        <label className="check boxed">
          <input
            type="checkbox"
            checked={accepted}
            onChange={(e) => setAccepted(e.target.checked)}
          />
          I approve model execution within this budget. Catalog publication
          requires a separate review.
        </label>
        <div className="form-actions">
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={onClose}
          >
            Back
          </button>
          <button
            className="button primary"
            disabled={busy || !budget || !accepted || !allocationValid}
          >
            {busy ? "Starting…" : "Start A2A workflow"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
/** Shared workflow controls use task-specific headings and preserve replies; feedback results render deterministic evidence separately from simulation trials. */
export function Workflow({
  workflowId,
  onBack,
  onPublished,
}: {
  workflowId: string;
  onBack: () => void;
  onPublished: () => void;
}) {
  const remote = useRemote<Data>("ai/workflows/" + id(workflowId), 4000),
    w = remote.data?.workflow,
    usage = remote.data?.usage;
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [artifact, setArtifact] = useState<Data>(),
    [preview, setPreview] = useState<Data>(),
    [candidate, setCandidate] = useState(""),
    [note, setNote] = useState(""),
    [confirmed, setConfirmed] = useState(false),
    [receipt, setReceipt] = useState<Data>(),
    [resume, setResume] = useState<Data>(),
    [cancel, setCancel] = useState(false),
    [offset, setOffset] = useState(0),
    [showTrials, setShowTrials] = useState(false);
  const trials = useRemote<Data>(
    showTrials
      ? "ai/workflows/" +
          id(workflowId) +
          "/evaluations?offset=" +
          offset +
          "&limit=20"
      : null,
  );
  async function action(path: string, body: Data) {
    setBusy(true);
    setError("");
    try {
      const v = await api(
        "ai/workflows/" + id(workflowId) + "/" + path,
        "POST",
        body,
      );
      remote.refresh();
      trials.refresh();
      return v;
    } catch (e) {
      setError(errorText(e));
      return null;
    } finally {
      setBusy(false);
    }
  }
  async function readArtifact(ref: string) {
    setBusy(true);
    setError("");
    try {
      setArtifact(
        await api("ai/workflows/" + id(workflowId) + "/artifacts/" + id(ref)),
      );
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  async function publish() {
    if (!preview || !confirmed) return;
    setBusy(true);
    setError("");
    try {
      setReceipt(
        await api(
          "ai/publication-previews/" + id(preview.previewId) + "/confirm",
          "POST",
          { previewHash: preview.previewHash, confirm: true },
        ),
      );
      setPreview(undefined);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  const validated = (w?.evaluations ?? []).filter(
    (e: Data) => e.phase === "VALIDATION" && e.status === "COMPLETED",
  );
  return (
    <>
      <PageTitle
        eyebrow={w?.network === "FEEDBACK" ? "Performance investigation" : "A2A exploration"}
        title={w?.network === "FEEDBACK" ? "Investigation details" : "Simulation details"}
        action={
          <button className="button secondary" onClick={onBack}>
            ← {w?.network === "FEEDBACK" ? "All investigations" : "All explorations"}
          </button>
        }
      >
        {w?.intent}
      </PageTitle>
      <Notice>{remote.error || error}</Notice>
      {!w ? (
        <Loading />
      ) : (
        <>
          <div className="section-toolbar">
            <Badge tone={w.state === "COMPLETED" ? "good" : ""}>
              {human(w.state)}
            </Badge>
            <button className="text-button" onClick={remote.refresh}>
              <Icon name="refresh" />
              Refresh
            </button>
          </div>
          <Notice tone="info">{w.lastActivity}</Notice>
          <Notice>
            {typeof w.error === "string" ? w.error : w.error?.message}
          </Notice>
          {receipt && (
            <section className="panel">
              <Badge tone="good">Published to catalog</Badge>
              <h2>{receipt.receipt?.offerId}</h2>
              <p>
                The saved prediction is now linked to this publication for
                recorded-outcome tracking.
              </p>
              <button className="button primary" onClick={onPublished}>
                View offer performance <Icon />
              </button>
              <Evidence value={receipt} label="Inspect publication receipt" />
            </section>
          )}
          {w.pendingQuestion && (
            <Question
              key={w.pendingQuestion.id}
              question={w.pendingQuestion}
              version={remote.data!.version}
              busy={busy}
              onAnswer={(body) => action("messages", body)}
            />
          )}
          <div className="two-columns">
            <section className="panel">
              <h2>Resource usage</h2>
              {w.network !== "FEEDBACK" && <Progress
                label="Evaluations"
                value={usage?.evaluations}
                max={usage?.limits?.maxEvaluations}
              />}
              <Progress
                label="Model calls"
                value={usage?.modelCalls}
                max={usage?.limits?.maxModelCalls}
              />
              <Progress
                label="Reserved tokens"
                value={usage?.reservedTokens}
                max={usage?.limits?.maxTokens}
              />
              <p className="muted">
                Reserved cost {money(usage?.reservedCostUsd)} /{" "}
                {money(usage?.limits?.maxCostUsd)}
                <br />
                Deadline {date(usage?.limits?.deadline)}
              </p>
              <Evidence value={usage} label="Inspect budget ledger" />
            </section>
            <section className="panel">
              <h2>Agent activity</h2>
              {Object.entries(w.steps ?? {}).map(([k, s]: [string, any]) => (
                <div className="activity-row" key={k}>
                  <span className="activity-dot" />
                  <div>
                    <b>{human(s.role)}</b>
                    <small>{human(k)}</small>
                  </div>
                  <button
                    className="text-button"
                    disabled={busy}
                    onClick={() => readArtifact(s.resultRef)}
                  >
                    Evidence <Icon />
                  </button>
                </div>
              ))}
              {!Object.keys(w.steps ?? {}).length && (
                <p className="muted">Completed agent steps will appear here.</p>
              )}
            </section>
          </div>
          {w.state === "AWAITING_SCOPE" && (
            <section className="panel">
              <h2>Review the proposed scope</h2>
              <pre className="review-json">
                {JSON.stringify(w.scope ?? w.ranges, null, 2)}
              </pre>
              <button
                className="button primary"
                disabled={busy}
                onClick={() =>
                  action("scope-confirmations", {
                    expectedVersion: remote.data!.version,
                    scopeHash: w.scopeHash,
                  })
                }
              >
                Approve this scope and continue
              </button>
            </section>
          )}
          {w.answer?.measurement ? <InvestigationAnswer answer={w.answer} onEvidence={readArtifact}/> : (w.analysis || w.reflection || w.answer) && (
            <section className="panel">
              <h2>Findings and reflection</h2>
              {w.answer && (
                <p className="preserve-lines">
                  {typeof w.answer === "string"
                    ? w.answer
                    : JSON.stringify(w.answer, null, 2)}
                </p>
              )}
              {w.analysis && (
                <Evidence
                  label="Analysis and supporting evidence"
                  value={w.analysis}
                />
              )}{" "}
              {w.reflection && (
                <Evidence
                  label="Reflection and limitations"
                  value={w.reflection}
                />
              )}
            </section>
          )}
          {w.network !== "FEEDBACK" && <section className="panel">
            <div className="panel-top">
              <h2>Saved trials</h2>
              <button
                className="text-button"
                onClick={() => {
                  setShowTrials((v) => !v);
                  trials.refresh();
                }}
              >
                {showTrials ? "Hide" : "Inspect"} evaluations
              </button>
            </div>
            <p>
              {number(w.evaluations?.length ?? 0)} evaluations in this revision.
              Reports preserve each tested configuration.
            </p>
            {showTrials && (
              <>
                <Notice>{trials.error}</Notice>
                {trials.data?.items?.map((e: Data, i: number) => (
                  <div className="trial-row" key={i}>
                    <div>
                      <Badge>{e.phase}</Badge>
                      <b>{human(e.status)}</b>
                      <small className="identifier">{e.candidateId}</small>
                    </div>
                    <Evidence value={e} label="Configuration and score" />
                    {e.reportRef && (
                      <button
                        className="text-button"
                        disabled={busy}
                        onClick={() => readArtifact(e.reportRef)}
                      >
                        Open report <Icon />
                      </button>
                    )}
                  </div>
                ))}
                <div className="pagination">
                  <button
                    className="button secondary"
                    disabled={!offset}
                    onClick={() => setOffset((n) => Math.max(0, n - 20))}
                  >
                    Previous
                  </button>
                  <button
                    className="button secondary"
                    disabled={trials.data?.nextOffset == null}
                    onClick={() => setOffset(trials.data!.nextOffset)}
                  >
                    Next
                  </button>
                </div>
              </>
            )}
          </section>}
          {w.state === "COMPLETED" && validated.length > 0 && !receipt && (
            <section className="panel">
              <h2>Review a validated candidate</h2>
              <p>
                Choose a final-validation result. The server will prepare the
                exact offer, rule changes, and prediction for a separate
                publication confirmation.
              </p>
              <form
                onSubmit={async (e) => {
                  e.preventDefault();
                  const p = await action("publication-previews", {
                    candidateId: candidate,
                    note,
                  });
                  if (p) {
                    setPreview(p);
                    setConfirmed(false);
                  }
                }}
              >
                <label className="field">
                  <span>Final-validation candidate</span>
                  <select
                    required
                    value={candidate}
                    onChange={(e) => setCandidate(e.target.value)}
                  >
                    <option value="">Select a candidate</option>
                    {validated.map((v: Data) => (
                      <option key={v.candidateId} value={v.candidateId}>
                        {v.candidateId.slice(0, 16)} · run{" "}
                        {v.runId?.slice(0, 12)}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="field">
                  <span>Review note</span>
                  <textarea
                    minLength={10}
                    maxLength={1000}
                    required
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                  />
                </label>
                <button className="button primary" disabled={busy}>
                  Prepare publication preview <Icon />
                </button>
              </form>
            </section>
          )}
          <div className="split-actions">
            {w.state === "PAUSED_BUDGET" && (
              <button
                className="button primary"
                onClick={() =>
                  setResume({
                    ...w.budget,
                    deadline: new Date(Date.now() + 30 * 60000).toISOString(),
                  })
                }
              >
                Review budget extension
              </button>
            )}
            {![
              "COMPLETED",
              "CANCELED",
              "FAILED",
              "BLOCKED",
              "NO_SUPPORTED_CANDIDATE",
            ].includes(w.state) && (
              <button
                className="button secondary danger"
                disabled={busy}
                onClick={() => setCancel(true)}
              >
                Cancel remaining work
              </button>
            )}
          </div>
          <Evidence value={w} label="Inspect complete workflow state" />
        </>
      )}
      {artifact && (
        <Modal
          title="Saved agent evidence"
          onClose={() => setArtifact(undefined)}
        >
          <pre className="review-json">{JSON.stringify(artifact, null, 2)}</pre>
        </Modal>
      )}
      {preview && (
        <Modal
          title="Confirm exact catalog publication"
          onClose={() => !busy && setPreview(undefined)}
        >
          <Badge>Explicit approval</Badge>
          <p>
            Expires {date(preview.expiresAt)}. This will publish the exact
            validated configuration below.
          </p>
          <h3>{preview.configuration?.offer?.name}</h3>
          <p>
            Predicted acceptance: {pct(preview.prediction?.estimatePct)} ·{" "}
            {human(preview.prediction?.scope)}
          </p>
          <h3>Changed fields</h3>
          <pre className="review-json">
            {JSON.stringify(preview.changedPaths, null, 2)}
          </pre>
          <h3>Exact offer and rules</h3>
          <pre className="review-json">
            {JSON.stringify(preview.configuration, null, 2)}
          </pre>
          <p>{preview.note}</p>
          <label className="check">
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(e) => setConfirmed(e.target.checked)}
            />
            I reviewed this configuration and approve publication to the
            catalog.
          </label>
          <Notice>{error}</Notice>
          <div className="form-actions">
            <button
              className="button secondary"
              disabled={busy}
              onClick={() => setPreview(undefined)}
            >
              Back
            </button>
            <button
              className="button primary"
              disabled={busy || !confirmed}
              onClick={publish}
            >
              {busy ? "Publishing…" : "Confirm publication"}
            </button>
          </div>
        </Modal>
      )}
      {resume && (
        <Modal
          title="Extend the workflow budget"
          onClose={() => !busy && setResume(undefined)}
        >
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              if (
                await action("resume", {
                  expectedVersion: remote.data!.version,
                  budget: resume,
                })
              )
                setResume(undefined);
            }}
          >
            <BudgetFields value={resume} onChange={setResume} extension />
            <Notice>{error}</Notice>
            <button className="button primary" disabled={busy}>
              Approve extension and resume
            </button>
          </form>
        </Modal>
      )}
      {cancel && (
        <Modal
          title="Cancel remaining work?"
          onClose={() => !busy && setCancel(false)}
        >
          <p>
            Completed evidence remains saved. Already dispatched provider work
            may finish and consume budget.
          </p>
          <Notice>{error}</Notice>
          <div className="form-actions">
            <button
              className="button secondary"
              disabled={busy}
              onClick={() => setCancel(false)}
            >
              Keep running
            </button>
            <button
              className="button primary"
              disabled={busy}
              onClick={async () => {
                if (await action("cancel", {})) setCancel(false);
              }}
            >
              Cancel workflow
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}
/** Only a new question identity resets this editor; polling preserves the analyst's multiline answer. */
function Question({
  question,
  version,
  busy,
  onAnswer,
}: {
  question: Data;
  version: number;
  busy: boolean;
  onAnswer: (body: Data) => Promise<Data | null>;
}) {
  const [text, setText] = useState("");
  const q = question.question;
  return (
    <section className="panel question-panel">
      <Badge tone="warm">Your input is needed</Badge>
      <span className="eyebrow">
        {human(question.agent)} · {q.topic}
      </span>
      <h2>{q.prompt}</h2>
      <p>{q.why}</p>
      <div className="choice-list">
        {q.choices?.map((c: any, i: number) => {
          const label =
            typeof c === "string"
              ? c
              : c.label || c.text || c.value || JSON.stringify(c);
          return (
            <button
              className="button secondary"
              key={i}
              onClick={() => setText(label)}
            >
              {label}
            </button>
          );
        })}
      </div>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          await onAnswer({
            expectedVersion: version,
            questionId: question.id,
            text,
          });
        }}
      >
        <label className="field">
          <span>Your answer</span>
          <textarea
            rows={5}
            required
            maxLength={6000}
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
        </label>
        <button className="button primary" disabled={busy || !text.trim()}>
          Send answer and continue <Icon />
        </button>
      </form>
    </section>
  );
}
/** Policy evidence links to the consolidated GUI guide; Research owns evidence and approvals bind the exact document hash. */
export function Policies() {
  const docs = useRemote<Data>("ai/policies");
  const [selected, setSelected] = useState<Data>(),
    [offset, setOffset] = useState(0),
    [product, setProduct] = useState("ALL"),
    [expires, setExpires] = useState(""),
    [reviewed, setReviewed] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const text = useRemote<Data>(
    selected
      ? "ai/policies/" + id(selected.id) + "/text?offset=" + offset
      : null,
  );
  async function refresh() {
    setBusy(true);
    setError("");
    try {
      await api("ai/policies/refresh", "POST", {});
      docs.refresh();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  async function approve(e: FormEvent) {
    e.preventDefault();
    if (!selected || !reviewed || !text.data) return;
    setBusy(true);
    setError("");
    try {
      await api("ai/policies/" + id(selected.id) + "/approve", "POST", {
        hash: text.data.document.hash,
        product,
        geography: "US",
        expiresOn: expires,
      });
      setSelected(undefined);
      docs.refresh();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <PageTitle
        eyebrow="Research evidence"
        title="Policy evidence"
        action={
          <button className="button primary" disabled={busy} onClick={refresh}>
            Refresh documents <Icon name="refresh" />
          </button>
        }
      >
        Place source documents in the project's policy-documents folder,
        refresh, then review and approve the extracted evidence.
      </PageTitle>
      <Notice>{error || docs.error}</Notice>
      <section className="panel">
        {docs.data?.documents?.length ? (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Document</th>
                  <th>Extraction</th>
                  <th>Approval</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {docs.data.documents.map((d: Data) => (
                  <tr key={d.id}>
                    <td>
                      <b>{d.name}</b>
                      <small>{number(d.characterCount)} characters</small>
                    </td>
                    <td>
                      <Badge>{d.extractionStatus}</Badge>
                    </td>
                    <td>
                      <Badge tone={d.approved ? "good" : ""}>
                        {d.approved ? "Approved" : "Review needed"}
                      </Badge>
                      {d.removed && <Badge tone="warm">Removed</Badge>}
                    </td>
                    <td>
                      <button
                        className="text-button"
                        disabled={d.removed || d.extractionStatus !== "READY"}
                        onClick={() => {
                          setSelected(d);
                          setOffset(0);
                          setReviewed(false);
                          setError("");
                        }}
                      >
                        Read and review <Icon />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty
            title={
              docs.loading
                ? "Loading policy documents…"
                : "No policy evidence available"
            }
          >
            A2A research uses reviewed local evidence. The extraction service
            supports the formats listed in the GUI walkthrough (README.md).
          </Empty>
        )}
      </section>
      {selected && (
        <Modal
          title={selected.name}
          onClose={() => !busy && setSelected(undefined)}
        >
          <Notice>{text.error || error}</Notice>
          <pre className="policy-text">
            {text.data?.text || "Loading extracted text…"}
          </pre>
          <div className="pagination">
            <button
              className="button secondary"
              disabled={!offset}
              onClick={() => setOffset((n) => Math.max(0, n - 8000))}
            >
              Previous text
            </button>
            <span>
              {offset + 1}–
              {Math.min(offset + 8000, text.data?.totalCharacters ?? 0)}
            </span>
            <button
              className="button secondary"
              disabled={offset + 8000 >= (text.data?.totalCharacters ?? 0)}
              onClick={() => setOffset((n) => n + 8000)}
            >
              Next text
            </button>
          </div>
          <form onSubmit={approve}>
            <label className="field">
              <span>Applicable product</span>
              <select
                value={product}
                onChange={(e) => setProduct(e.target.value)}
              >
                {["ALL", "PERSONAL_LOAN", "CREDIT_CARD", "AUTO_LOAN"].map(
                  (p) => (
                    <option key={p}>{p}</option>
                  ),
                )}
              </select>
            </label>
            <Field
              name="expires"
              label="Approval expiry"
              type="date"
              value={expires}
              onChange={setExpires}
              required
            />
            <label className="check">
              <input
                type="checkbox"
                checked={reviewed}
                onChange={(e) => setReviewed(e.target.checked)}
              />
              I have read this document and approve its use as US policy
              evidence for the selected product.
            </label>
            <button
              className="button primary"
              disabled={busy || !reviewed || !text.data || Boolean(text.error)}
            >
              Approve this document version
            </button>
          </form>
        </Modal>
      )}
    </>
  );
}

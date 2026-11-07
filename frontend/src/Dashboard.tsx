import { useState } from "react";
import { type Data, id } from "./api";
import { date, human, number, pct, performanceView } from "./model";
import {
  Badge,
  Empty,
  Evidence,
  Icon,
  Notice,
  PageTitle,
  Stat,
  useRemote,
} from "./ui";

/** Presents recorded performance with plain headings and empty states from the deterministic API, without starting agents; eligibility and acceptance remain distinct. */
export function Dashboard({
  onInvestigate,
  onRun,
  onNew,
}: {
  onInvestigate: (id: string) => void;
  onRun: (id: string) => void;
  onNew: () => void;
}) {
  const [after, setAfter] = useState(""),
    [history, setHistory] = useState<string[]>([]),
    [mode, setMode] = useState("DEMO"),
    [revision, setRevision] = useState(0);
  const publications = useRemote<Data[]>(
    "ai/performance/publications" + (after ? "?after=" + id(after) : ""),
  );
  const manual = useRemote<Data[]>("business/publications");
  const tracked = new Set((publications.data ?? []).map((p) => p.id));
  const rows = [...(publications.data ?? [])].sort((a, b) =>
    String(b.data.preview?.receipt?.publishedAt).localeCompare(
      String(a.data.preview?.receipt?.publishedAt),
    ),
  );
  function refresh() {
    publications.refresh();
    manual.refresh();
    setRevision((r) => r + 1);
  }
  return (
    <>
      <PageTitle
        eyebrow="Business intelligence"
        title="Offer performance"
        action={
          <button className="button secondary" onClick={refresh}>
            <Icon name="refresh" />
            Refresh outcomes
          </button>
        }
      />
      <div className="dashboard-intro">
        <div>
          <Badge tone="good">Recorded outcomes only</Badge>
          <p>
            Predictions stay frozen. Performance reflects the selected source
            and its available follow-up.
          </p>
        </div>
        <label className="field compact">
          <span>Observation source</span>
          <select value={mode} onChange={(e) => setMode(e.target.value)}>
            <option value="DEMO">Local demo outcomes</option>
            <option value="OBSERVED_REAL">
              Configured real-source outcomes
            </option>
          </select>
        </label>
      </div>
      <div className="stats-grid">
        <Stat
          label="AI publications on this page"
          value={publications.loading && !publications.data ? "…" : rows.length}
          detail="Saved, explicitly published experiments"
        />
        <Stat
          label="Engine publication receipts"
          value={manual.data?.length ?? "—"}
          detail="Manual and AI catalog publications"
        />
        <Stat
          label="Outcome source"
          value={
            <span className="compact-stat">
              {mode === "DEMO" ? "Local demo" : "Observed real"}
            </span>
          }
          detail="Sources are never pooled"
        />
      </div>
      <div className="section-toolbar">
        <div>
          <span className="eyebrow">Prediction → performance</span>
          <h2>Published AI offers</h2>
        </div>
        <button className="button primary" onClick={onNew}>
          New experiment <Icon />
        </button>
      </div>
      <Notice>{publications.error}</Notice>
      {publications.error && (
        <Notice tone="info">
          Manual simulations remain available. Start the Performance API with scripts/run-ai.ps1 -PerformanceOnly to load
          tracked AI publications and their feedback.
        </Notice>
      )}
      {rows.length ? (
        <div className="performance-grid">
          {rows.map((p) => (
            <PerformanceCard
              key={p.id + mode + revision}
              publication={p}
              mode={mode}
              onInvestigate={() => onInvestigate(p.id)}
            />
          ))}
        </div>
      ) : (
        <Empty
          title={
            publications.loading
              ? "Loading published offers…"
              : "No published AI offers"
          }
        >
          Completed simulations remain drafts until you review and publish them.
          Published AI offers and their recorded performance will appear here
          automatically.
        </Empty>
      )}
      <div className="pagination">
        {history.length > 0 && (
          <button
            className="button secondary"
            onClick={() => {
              setAfter(history[history.length - 1]);
              setHistory((h) => h.slice(0, -1));
            }}
          >
            Previous publications
          </button>
        )}
        {publications.data?.length === 20 && (
          <button
            className="button secondary"
            onClick={() => {
              setHistory((h) => [...h, after]);
              setAfter(publications.data![19].id);
            }}
          >
            More publications <Icon />
          </button>
        )}
      </div>
      <section className="panel">
        <div className="panel-top">
          <div>
            <span className="eyebrow">Catalog history</span>
            <h2>All publication receipts</h2>
          </div>
          <span className="muted">Includes manual experiments</span>
        </div>
        <Notice>{manual.error}</Notice>
        {manual.data?.length ? (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Catalog offer</th>
                  <th>Published</th>
                  <th>Rule version</th>
                  <th>Evidence</th>
                </tr>
              </thead>
              <tbody>
                {[...manual.data].reverse().map((p) => (
                  <tr key={p.offerId}>
                    <td>
                      <b>{p.offerId}</b>
                      <small>{p.reviewNote}</small>
                    </td>
                    <td>{date(p.publishedAt)}</td>
                    <td>{p.ruleVersion}</td>
                    <td>
                      <button
                        className="text-button"
                        onClick={() => onRun(p.runId)}
                      >
                        View tested run <Icon />
                      </button>
                      {tracked.has(p.offerId) && <Badge>AI tracked</Badge>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="muted">No publication receipts are available.</p>
        )}
        <p className="footnote">
          AI feedback is available for publications linked to an AI workflow.
          Manual receipts retain their tested simulation report; missing
          observations are not treated as zero.
        </p>
      </section>
    </>
  );
}
function PerformanceCard({
  publication,
  mode,
  onInvestigate,
}: {
  publication: Data;
  mode: string;
  onInvestigate: () => void;
}) {
  const p = publication.data,
    remote = useRemote<Data>(
      "ai/performance/publications/" +
        id(publication.id) +
        "?sourceMode=" +
        mode,
    );
  const view = remote.data ? performanceView(remote.data) : null;
  const expected = view ? view.expected : p.prediction?.estimatePct;
  const original = p.prediction?.estimatePct;
  const actual = view?.actual;
  const notes: Record<string, string> = {
    TARGET_MISMATCH:
      "The saved simulation covers catalog terms; recorded acceptance covers the offer family. These values are not a validated forecast-error comparison.",
    COHORT_MISMATCH:
      "Prediction and observations cover different customer cohorts.",
    INCOMPLETE_DATA:
      "Outcome coverage is incomplete; a forecast gap is unavailable.",
    PROVISIONAL: "The follow-up window is still open.",
    DIAGNOSTIC_ONLY:
      "Use these values as descriptive diagnostics; this prediction is not a calibrated real forecast.",
    MATCH: "The backend matched the prediction and observed cohort.",
  };
  return (
    <article className="panel performance-card">
      <div className="panel-top">
        <div>
          <span className="eyebrow">
            {human(p.preview?.configuration?.offer?.product)}
          </span>
          <h2>{p.preview?.configuration?.offer?.name || publication.id}</h2>
        </div>
        <Badge tone={view?.observed.maturity === "MATURE" ? "good" : ""}>
          {remote.loading
            ? "Loading outcomes"
            : human(view?.observed.maturity || "Unavailable")}
        </Badge>
      </div>
      <p className="muted">Published {date(p.preview?.receipt?.publishedAt)}</p>
      <div className="performance-numbers">
        <div>
          <span>
            {mode === "OBSERVED_REAL"
              ? "Comparison prediction"
              : "Original predicted acceptance"}
          </span>
          <strong>{pct(expected)}</strong>
          <small>
            {human(
              view?.prediction.predictionKind || p.prediction?.predictionKind,
            )}
          </small>
        </div>
        <span className="comparison-arrow">→</span>
        <div>
          <span>Recorded acceptance</span>
          <strong className="observed">{pct(actual)}</strong>
          <small>
            {view
              ? number(view.observed.Y) +
                " selected / " +
                number(view.observed.E) +
                " eligible"
              : remote.error
                ? "Source unavailable"
                : "Waiting for recorded outcomes"}
          </small>
        </div>
      </div>
      <p className="muted">Original predicted eligibility: {pct(remote.data?.eligibilityComparison?.predictedPct)} · Recorded eligibility: {pct(remote.data?.eligibilityComparison?.observedPct)}. Different cohorts remain diagnostic comparisons.</p>
      <div
        className="comparison-bars"
        aria-label="Predicted and recorded acceptance"
      >
        <div>
          <span>Predicted</span>
          <div className="bar-track">
            <i
              style={{
                width:
                  typeof expected === "number"
                    ? Math.max(0, Math.min(100, expected)) + "%"
                    : "0%",
              }}
            />
          </div>
        </div>
        <div>
          <span>Recorded</span>
          <div className="bar-track actual">
            <i
              style={{
                width:
                  typeof actual === "number"
                    ? Math.max(0, Math.min(100, actual)) + "%"
                    : "0%",
              }}
            />
          </div>
        </div>
      </div>
      <Notice>{remote.error}</Notice>
      {view?.stale && (
        <Notice tone="warning">
          The source could not refresh. These are previously recorded
          observations.
        </Notice>
      )}
      {view && (
        <>
          <div className="performance-meta">
            <span>
              <b>{number(view.observed.matureEnrollments)}</b> mature
            </span>
            <span>
              <b>{number(view.observed.provisionalEnrollments)}</b> awaiting
              follow-up
            </span>
            <span>{view.observed.followUpDays}-day follow-up</span>
          </div>
          <div className="comparison-note">
            <Badge tone={view.gap !== null ? "good" : "warm"}>
              {human(view.status)}
            </Badge>
            <p>
              {view.gap !== null
                ? "Validated gap: " +
                  view.gap.toFixed(1) +
                  " percentage points."
                : notes[view.status] ||
                  "A comparable forecast gap is not available."}
            </p>
            {view.unavailable && (
              <p>No eligible observed denominator is available yet.</p>
            )}
          </div>
          {mode === "OBSERVED_REAL" && (
            <p className="footnote">
              Original simulation estimate retained: {pct(original)}
            </p>
          )}
          <div className="card-footer">
            <small>
              As of {date(view.observed.asOf)}
              <br />
              {human(view.observed.coverage)}
            </small>
            <button className="text-button" onClick={onInvestigate}>
              Investigate with A2A <Icon />
            </button>
          </div>
          <Evidence
            value={{
              comparison: remote.data?.comparison,
              sourceRefresh: remote.data?.sourceRefresh,
              snapshotRef: remote.data?.snapshotRef,
            }}
            label="Inspect counts, coverage, and comparison evidence"
          />
        </>
      )}
    </article>
  );
}

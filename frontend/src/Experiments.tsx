import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, errorText, id, key, type Data } from "./api";
import {
  bureauFields,
  date,
  human,
  labels,
  money,
  number,
  offerFields,
  pct,
  stageFields,
} from "./model";
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
  Stat,
  useRemote,
} from "./ui";

/** Manual experiment screens use plain headings and empty states for immutable engine drafts and isolated populations; publication is an explicit command. */
export function Experiments({
  initialRun,
  onAi,
}: {
  initialRun: string;
  onAi: (id: string) => void;
}) {
  const [tab, setTab] = useState("drafts"),
    [draft, setDraft] = useState<Data>(),
    [runId, setRunId] = useState(initialRun),
    [modal, setModal] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const drafts = useRemote<Data[]>("business/drafts"),
    pops = useRemote<Data[]>("business/populations"),
    runs = useRemote<Data[]>("business/runs"),
    catalog = useRemote<Data>("business/catalog");
  const [count, setCount] = useState(1000),
    [seed, setSeed] = useState(20261021),
    [name, setName] = useState(""),
    [offerId, setOfferId] = useState(""),
    [campaignId, setCampaignId] = useState(""),
    [populationId, setPopulationId] = useState("");
  const requests = useRef(new Map<string, string>());
  const [dirty, setDirty] = useState(false);
  function operation(path: string, body: Data) {
    const fingerprint = path + JSON.stringify(body);
    if (!requests.current.has(fingerprint))
      requests.current.set(fingerprint, key());
    return requests.current.get(fingerprint)!;
  }
  async function create(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      if (modal === "population") {
        const body = { count, seed };
        await api(
          "business/populations",
          "POST",
          body,
          operation("population", body),
        );
        pops.refresh();
        setNotice(
          "Isolated population created. Active customers were not changed.",
        );
      }
      if (modal === "draft") {
        const body = { name, baselineOfferId: offerId, campaignId };
        const value = await api(
          "business/drafts",
          "POST",
          body,
          operation("draft", body),
        );
        setDraft(value);
        drafts.refresh();
      }
      if (modal === "run" && draft) {
        const body = {
          draftId: draft.id,
          draftVersion: draft.version,
          populationId,
          seed,
        };
        const value = await api(
          "business/runs",
          "POST",
          body,
          operation("run", body),
        );
        setRunId(value.id);
        setDraft(undefined);
        runs.refresh();
      }
      requests.current.clear();
      setModal("");
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  async function openDraft(idValue: string) {
    setBusy(true);
    setError("");
    try {
      setDraft(await api("business/drafts/" + id(idValue)));
      setRunId("");
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  if (runId)
    return (
      <RunDetail
        runId={runId}
        onBack={() => {
          setRunId("");
          runs.refresh();
        }}
      />
    );
  return (
    <>
      <PageTitle
        eyebrow="Manual experiments"
        title={draft ? "Edit offer draft" : "Simulations"}
        action={
          draft ? (
            <button
              className="button secondary"
              onClick={() => setDraft(undefined)}
            >
              ← All experiments
            </button>
          ) : (
            <button
              className="button primary"
              onClick={() => {
                setModal("draft");
                setError("");
              }}
            >
              New draft <Icon />
            </button>
          )
        }
      />
      <Notice>{error}</Notice>
      <Notice tone="success">{notice}</Notice>
      {draft ? (
        <>
          <DraftEditor
            key={draft.id + ":" + draft.version}
            draft={draft}
            onDirty={setDirty}
            onSaved={(d) => {
              setDirty(false);
              setDraft(d);
              drafts.refresh();
            }}
            onReload={() => openDraft(draft.id)}
          />
          {dirty && (
            <Notice tone="info">
              Save your draft changes before starting a manual or A2A
              experiment.
            </Notice>
          )}
          <div className="split-actions">
            <button
              className="button secondary"
              disabled={dirty}
              onClick={() => onAi(draft.id)}
            >
              <Icon name="spark" />
              Explore this draft with A2A
            </button>
            <button
              className="button primary"
              disabled={dirty}
              onClick={() => {
                setModal("run");
                setError("");
              }}
            >
              Run a comparison <Icon />
            </button>
          </div>
        </>
      ) : (
        <>
          <div className="subnav">
            {[
              ["drafts", "Offer drafts"],
              ["populations", "Populations"],
              ["runs", "Simulation results"],
            ].map(([value, label]) => (
              <button
                className={tab === value ? "active" : ""}
                onClick={() => setTab(value)}
                key={value}
              >
                {label}
                <span>
                  {value === "drafts"
                    ? (drafts.data?.length ?? 0)
                    : value === "populations"
                      ? (pops.data?.length ?? 0)
                      : (runs.data?.length ?? 0)}
                </span>
              </button>
            ))}
          </div>
          <Notice>
            {drafts.error || pops.error || runs.error || catalog.error}
          </Notice>
          {tab === "drafts" &&
            (drafts.data?.length ? (
              <div className="draft-grid">
                {[...drafts.data].reverse().map((d) => (
                  <article className="panel draft-card" key={d.id}>
                    <div className="panel-top">
                      <Badge>{human(d.offer.product)}</Badge>
                      <span className="muted">v{d.version}</span>
                    </div>
                    <h2>{d.name}</h2>
                    <p>{d.offer.name}</p>
                    <div className="draft-terms">
                      <div>
                        <strong>{pct(d.offer.illustrativeAprPct)}</strong>
                        <small>APR</small>
                      </div>
                      <div>
                        <strong>{money(d.offer.maximumAmountUsd)}</strong>
                        <small>Maximum amount</small>
                      </div>
                    </div>
                    <div className="card-footer">
                      <small>{date(d.createdAt)}</small>
                      <button
                        className="text-button"
                        disabled={busy}
                        onClick={() => openDraft(d.id)}
                      >
                        Open draft <Icon />
                      </button>
                    </div>
                  </article>
                ))}
              </div>
            ) : (
              <Empty title="No saved drafts">
                Choose an existing catalog offer, adjust its terms and rules,
                then compare the results before publication.
              </Empty>
            ))}
          {tab === "populations" && (
            <section className="panel">
              <div className="panel-top">
                <h2>Frozen synthetic populations</h2>
                <button
                  className="button primary"
                  onClick={() => {
                    setModal("population");
                    setError("");
                  }}
                >
                  Generate population <Icon />
                </button>
              </div>
              <p className="muted">
                Use different seeds for AI discovery and final validation. These
                customers stay outside the active pipeline.
              </p>
              {pops.data?.length ? (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>Population</th>
                        <th>Customers</th>
                        <th>Seed</th>
                        <th>Frozen at</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {pops.data.map((p) => (
                        <tr key={p.id}>
                          <td className="identifier">{p.id}</td>
                          <td>{number(p.count)}</td>
                          <td>{p.seed}</td>
                          <td>{date(p.asOf)}</td>
                          <td>
                            <button
                              className="text-button"
                              onClick={() => {
                                setPopulationId(p.id);
                                setModal("people");
                              }}
                            >
                              Inspect
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <Empty title="No populations yet">
                  Generate 60–10,000 reproducible synthetic customers to run an
                  experiment.
                </Empty>
              )}
            </section>
          )}
          {tab === "runs" &&
            (runs.data?.length ? (
              <section className="panel table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>Experiment</th>
                      <th>Status</th>
                      <th>Draft version</th>
                      <th>Created</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {[...runs.data].reverse().map((r) => (
                      <tr key={r.id}>
                        <td>
                          <b>{r.draft?.name}</b>
                          <small className="identifier">{r.id}</small>
                        </td>
                        <td>
                          <Badge tone={r.status === "COMPLETED" ? "good" : ""}>
                            {human(r.status)}
                          </Badge>
                        </td>
                        <td>v{r.draft?.version}</td>
                        <td>{date(r.createdAt)}</td>
                        <td>
                          <button
                            className="text-button"
                            onClick={() => setRunId(r.id)}
                          >
                            View results <Icon />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
            ) : (
              <Empty title="No saved comparisons">
                Open a draft and run it against a saved population.
              </Empty>
            ))}
        </>
      )}
      {modal && modal !== "people" && (
        <Modal
          title={
            modal === "population"
              ? "Generate a population"
              : modal === "draft"
                ? "Create an offer draft"
                : "Run a manual comparison"
          }
          onClose={() => !busy && setModal("")}
        >
          <form onSubmit={create}>
            <Notice>{error}</Notice>
            {modal === "population" ? (
              <div className="form-grid">
                <Field
                  name="count"
                  label="Customer count"
                  type="number"
                  min={60}
                  max={10000}
                  step={1}
                  value={count}
                  onChange={(v) => setCount(Number(v))}
                  required
                />
                <Field
                  name="seed"
                  label="Population seed"
                  type="number"
                  step={1}
                  value={seed}
                  onChange={(v) => setSeed(Number(v))}
                  required
                />
              </div>
            ) : modal === "draft" ? (
              <>
                <Field
                  name="name"
                  label="Experiment name"
                  value={name}
                  onChange={setName}
                  required
                />
                <label className="field">
                  <span>Baseline catalog offer</span>
                  <select
                    required
                    value={offerId}
                    onChange={(e) => {
                      setOfferId(e.target.value);
                      setCampaignId("");
                    }}
                  >
                    <option value="">Select an offer</option>
                    {catalog.data?.offers?.map((o: Data) => (
                      <option key={o.id} value={o.id}>
                        {o.data.name} · {human(o.data.product)}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="field">
                  <span>Campaign containing this offer</span>
                  <select
                    required
                    value={campaignId}
                    onChange={(e) => setCampaignId(e.target.value)}
                  >
                    <option value="">Select a campaign</option>
                    {catalog.data?.campaigns
                      ?.filter((c: Data) => c.data.offerIds.includes(offerId))
                      .map((c: Data) => (
                        <option key={c.id} value={c.id}>
                          {c.data.name}
                        </option>
                      ))}
                  </select>
                </label>
              </>
            ) : (
              <>
                <p>
                  Testing <b>{draft?.name}</b>, version {draft?.version}. This
                  creates a saved comparison and does not publish.
                </p>
                <label className="field">
                  <span>Population</span>
                  <select
                    required
                    value={populationId}
                    onChange={(e) => setPopulationId(e.target.value)}
                  >
                    <option value="">Select a population</option>
                    {pops.data?.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.count} customers · seed {p.seed} · {p.id.slice(0, 8)}
                      </option>
                    ))}
                  </select>
                </label>
                {!pops.data?.length && (
                  <Notice tone="info">
                    Generate a population from the Populations tab first.
                  </Notice>
                )}
                <Field
                  name="seed"
                  label="Analysis seed"
                  type="number"
                  step={1}
                  value={seed}
                  onChange={(v) => setSeed(Number(v))}
                  required
                />
              </>
            )}
            <div className="form-actions">
              <button
                className="button secondary"
                type="button"
                disabled={busy}
                onClick={() => setModal("")}
              >
                Cancel
              </button>
              <button className="button primary" disabled={busy}>
                {busy
                  ? "Submitting…"
                  : modal === "run"
                    ? "Start comparison"
                    : modal === "draft"
                      ? "Create draft"
                      : "Generate population"}
              </button>
            </div>
          </form>
        </Modal>
      )}
      {modal === "people" && (
        <Modal title="Synthetic population" onClose={() => setModal("")}>
          <PopulationPeople populationId={populationId} />
        </Modal>
      )}
    </>
  );
}
function PopulationPeople({ populationId }: { populationId: string }) {
  const [offset, setOffset] = useState(0);
  const people = useRemote<Data>(
    "business/populations/" +
      id(populationId) +
      "/customers?offset=" +
      offset +
      "&limit=10",
  );
  return (
    <>
      <Notice>{people.error}</Notice>
      {people.data?.items?.map((p: Data) => (
        <Evidence
          key={p.id}
          value={p}
          label={p.id + " · independent synthetic facts"}
        />
      ))}
      <div className="form-actions">
        <button
          className="button secondary"
          disabled={!offset}
          onClick={() => setOffset((n) => Math.max(0, n - 10))}
        >
          Previous
        </button>
        <button
          className="button secondary"
          disabled={offset + 10 >= (people.data?.total ?? 0)}
          onClick={() => setOffset((n) => n + 10)}
        >
          Next
        </button>
      </div>
    </>
  );
}
function DraftEditor({
  draft,
  onSaved,
  onReload,
  onDirty,
}: {
  draft: Data;
  onSaved: (d: Data) => void;
  onReload: () => void;
  onDirty: (dirty: boolean) => void;
}) {
  const [form, setForm] = useState<Data>(structuredClone(draft)),
    [section, setSection] = useState("offer"),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [saved, setSaved] = useState(false);
  const fields =
    section === "offer"
      ? offerFields
      : section === "bureau"
        ? bureauFields
        : stageFields;
  const current = section === "offer" ? form.offer : form.rules[section];
  const original = section === "offer" ? draft.offer : draft.rules[section];
  useEffect(
    () => onDirty(JSON.stringify(form) !== JSON.stringify(draft)),
    [form, draft, onDirty],
  );
  function change(field: string, value: unknown) {
    setSaved(false);
    setForm((f) =>
      section === "offer"
        ? { ...f, offer: { ...f.offer, [field]: value } }
        : {
            ...f,
            rules: {
              ...f.rules,
              [section]: { ...f.rules[section], [field]: value },
            },
          },
    );
  }
  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const value = await api("business/drafts/" + id(draft.id), "PUT", {
        expectedVersion: draft.version,
        name: form.name,
        offer: form.offer,
        rules: form.rules,
      });
      setSaved(true);
      onSaved(value);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className="panel" onSubmit={save}>
      <div className="panel-top">
        <div>
          <Badge>{human(draft.offer.product)}</Badge>
          <h2>{draft.name}</h2>
        </div>
        <Badge>Saved version {draft.version}</Badge>
      </div>
      <Field
        name="experimentName"
        label="Experiment name"
        value={form.name}
        onChange={(v) => setForm((f) => ({ ...f, name: v }))}
        required
      />
      <div className="subnav">
        {["offer", "underwriting", "marketing", "bureau"].map((s) => (
          <button
            type="button"
            key={s}
            className={section === s ? "active" : ""}
            onClick={() => setSection(s)}
          >
            {human(s)}
          </button>
        ))}
      </div>
      <div className="form-grid">
        {fields.map((field) =>
          typeof original[field] === "boolean" ? (
            <label className="check boxed" key={field}>
              <input
                type="checkbox"
                checked={current[field]}
                onChange={(e) => change(field, e.target.checked)}
              />
              {labels[field] || human(field)}
            </label>
          ) : (
            <Field
              key={field}
              name={field}
              label={labels[field] || human(field)}
              value={current[field]}
              onChange={(v) =>
                change(
                  field,
                  typeof original[field] === "number"
                    ? v === ""
                      ? ""
                      : Number(v)
                    : v,
                )
              }
              type={
                field.endsWith("On")
                  ? "date"
                  : typeof original[field] === "number"
                    ? "number"
                    : "text"
              }
              min={0}
              step={
                field.endsWith("Usd") || field === "illustrativeAprPct"
                  ? "0.01"
                  : 1
              }
              required
            />
          ),
        )}
      </div>
      <p className="footnote">
        Unedited fields are preserved. Consent, suppression, and bureau identity
        checks remain enforced by the backend.
      </p>
      <Notice>{error}</Notice>
      {saved && <Notice tone="success">Draft saved.</Notice>}
      <div className="form-actions">
        <button type="button" className="button secondary" onClick={onReload}>
          Reload saved version
        </button>
        <button className="button primary" disabled={busy}>
          {busy ? "Saving…" : "Save draft version"}
        </button>
      </div>
    </form>
  );
}
export function Comparison({ report }: { report: Data }) {
  const section = report.overall ?? {},
    base = section.baseline ?? {},
    candidate = section.candidate ?? {};
  return (
    <>
      <div className="stats-grid">
        <Stat
          label="Synthetic customers assessed"
          value={number(section.population)}
        />
        <Stat label="Newly eligible" value={number(section.newlyEligible)} />
        <Stat
          label="Lost eligibility"
          value={number(section.lostEligibility)}
        />
      </div>
      <section className="panel">
        <div className="panel-top">
          <h2>Baseline vs. candidate</h2>
          <Badge>Simulated utility</Badge>
        </div>
        <table>
          <thead>
            <tr>
              <th>Metric</th>
              <th>Baseline</th>
              <th>Candidate</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Eligible customers</td>
              <td>{number(base.eligible)}</td>
              <td>{number(candidate.eligible)}</td>
            </tr>
            <tr>
              <td>Eligibility rate</td>
              <td>{pct(base.eligibilityPct)}</td>
              <td>{pct(candidate.eligibilityPct)}</td>
            </tr>
            <tr>
              <td>Predicted acceptance among eligible</td>
              <td>{pct(base.simulatedAcceptanceAmongEligiblePct)}</td>
              <td>{pct(candidate.simulatedAcceptanceAmongEligiblePct)}</td>
            </tr>
            <tr>
              <td>Mean monthly payment</td>
              <td>{money(base.monthlyPaymentUsd?.mean)}</td>
              <td>{money(candidate.monthlyPaymentUsd?.mean)}</td>
            </tr>
            <tr>
              <td>Mean borrowing cost</td>
              <td>{money(base.costUsd?.mean)}</td>
              <td>{money(candidate.costUsd?.mean)}</td>
            </tr>
          </tbody>
        </table>
        <p className="footnote">
          Acceptance is a simulated utility estimate, not observed conversion.
          Null metrics remain unavailable.
        </p>
      </section>
      <div className="two-columns">
        <section className="panel">
          <h2>Held-out test</h2>
          <Evidence
            value={report.heldOutTest}
            label="Inspect held-out comparison"
          />
        </section>
        <section className="panel">
          <h2>Cohort impact</h2>
          {Object.entries(report.cohorts ?? {}).map(([k, v]) => (
            <Evidence key={k} value={v} label={k} />
          ))}
        </section>
      </div>
      <section className="panel">
        <h2>Assumptions and evidence</h2>
        <ul className="assumptions">
          {report.assumptions?.map((a: string) => (
            <li key={a}>{a}</li>
          ))}
        </ul>
        <Evidence
          value={report}
          label="Inspect full report and model artifact"
        />
      </section>
    </>
  );
}
export function RunDetail({
  runId,
  onBack,
}: {
  runId: string;
  onBack: () => void;
}) {
  const [poll, setPoll] = useState(2500),
    [publish, setPublish] = useState(false),
    [note, setNote] = useState(""),
    [confirmed, setConfirmed] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [newReceipt, setReceipt] = useState<Data>(),
    [offset, setOffset] = useState(0),
    [showRows, setShowRows] = useState(false);
  const publications = useRemote<Data[]>("business/publications");
  const receipt =
    newReceipt ?? publications.data?.find((p) => p.runId === runId);
  const run = useRemote<Data>("business/runs/" + id(runId), poll);
  const rows = useRemote<Data[]>(
    showRows
      ? "business/runs/" + id(runId) + "/rows?offset=" + offset + "&limit=20"
      : null,
  );
  useEffect(() => {
    if (run.data && ["COMPLETED", "FAILED"].includes(run.data.status))
      setPoll(0);
  }, [run.data]);
  async function commit(e: FormEvent) {
    e.preventDefault();
    if (!confirmed) return;
    setBusy(true);
    setError("");
    try {
      setReceipt(
        await api("business/runs/" + id(runId) + "/publish", "POST", {
          confirm: true,
          note,
        }),
      );
      setPublish(false);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <PageTitle
        eyebrow="Simulation results"
        title={run.data?.draft?.name || "Your experiment"}
        action={
          <button className="button secondary" onClick={onBack}>
            ← Experiments
          </button>
        }
      >
        Frozen draft v{run.data?.draft?.version ?? "—"} ·{" "}
        {date(run.data?.createdAt)}
      </PageTitle>
      <Notice>{run.error || error}</Notice>
      {!run.data ? (
        <Loading />
      ) : (
        <>
          <div className="section-toolbar">
            <Badge tone={run.data.status === "COMPLETED" ? "good" : ""}>
              {human(run.data.status)}
            </Badge>
            {run.data.status === "COMPLETED" && !receipt && (
              <button
                className="button primary"
                onClick={() => setPublish(true)}
              >
                Review publication <Icon />
              </button>
            )}
          </div>
          {run.data.error && <Notice>{run.data.error}</Notice>}
          {receipt && (
            <section className="panel">
              <Badge tone="good">Published to catalog</Badge>
              <h2>{receipt.offerId}</h2>
              <p>{receipt.reviewNote}</p>
              <Evidence value={receipt} label="Publication receipt" />
            </section>
          )}
          {run.data.report ? (
            <>
              <Comparison report={run.data.report} />
              <section className="panel">
                <button
                  className="text-button"
                  onClick={() => setShowRows((v) => !v)}
                >
                  {showRows ? "Hide" : "Inspect"} customer-level outcomes
                </button>
                {showRows && (
                  <>
                    <Notice>{rows.error}</Notice>
                    {rows.data?.map((r) => (
                      <Evidence
                        key={r.customerId}
                        label={r.customerId + " · paired outcome"}
                        value={r}
                      />
                    ))}
                    <div className="form-actions">
                      <button
                        className="button secondary"
                        disabled={!offset}
                        onClick={() => setOffset((v) => Math.max(0, v - 20))}
                      >
                        Previous
                      </button>
                      <button
                        className="button secondary"
                        disabled={(rows.data?.length ?? 0) < 20}
                        onClick={() => setOffset((v) => v + 20)}
                      >
                        Next
                      </button>
                    </div>
                  </>
                )}
              </section>
            </>
          ) : (
            run.data.status !== "FAILED" && (
              <Empty title="The engine is comparing your offers">
                This page refreshes while the experiment is queued or running.
                You can leave and reopen it later.
              </Empty>
            )
          )}
        </>
      )}
      {publish && (
        <Modal
          title="Publish this tested offer"
          onClose={() => !busy && setPublish(false)}
        >
          <form onSubmit={commit}>
            <p>
              Publishing creates a new active catalog offer and campaign from
              this tested draft. Review the full terms and rule changes below.
            </p>
            <Evidence
              label="Inspect exact tested offer and rules"
              value={{
                offer: run.data?.draft?.offer,
                rules: run.data?.draft?.rules,
                baseline: run.data?.draft?.baseline,
              }}
            />
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
            <label className="check">
              <input
                type="checkbox"
                checked={confirmed}
                onChange={(e) => setConfirmed(e.target.checked)}
              />
              I reviewed these terms, rules, and the comparison results.
            </label>
            <Notice>{error}</Notice>
            <div className="form-actions">
              <button
                className="button secondary"
                type="button"
                disabled={busy}
                onClick={() => setPublish(false)}
              >
                Back
              </button>
              <button className="button primary" disabled={busy || !confirmed}>
                {busy ? "Publishing…" : "Confirm publication"}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </>
  );
}

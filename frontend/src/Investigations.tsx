import { useRef, useState, type FormEvent } from "react";
import { api, errorText, id, key, type Data } from "./api";
import { Workflow } from "./Ai";
import { Badge, Empty, Field, Notice, PageTitle, useRemote } from "./ui";
import { date, human } from "./model";

/** Presents a plain investigation form with scope and explicit spend approval; retrieval, calculations and clarification stay behind APIs. */
export function Investigations({ publicationId = "" }: { publicationId?: string }) {
  const publications = useRemote<Data[]>("ai/performance/publications");
  const defaults = useRemote<Data>("ai/defaults");
  const [after, setAfter] = useState("");
  const history = useRemote<Data[]>("ai/feedback/workflows?limit=20" + (after ? "&after=" + id(after) : ""));
  const [selected, setSelected] = useState("");
  const [publication, setPublication] = useState(publicationId);
  const [question, setQuestion] = useState("");
  const [customer, setCustomer] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [timezone, setTimezone] = useState("America/Los_Angeles");
  const [basis, setBasis] = useState("FIRST_ASSESSED");
  const [sourceMode, setSourceMode] = useState("DEMO");
  const [variant, setVariant] = useState("");
  const [cutoff, setCutoff] = useState("");
  const [calls, setCalls] = useState("30"), [tokens, setTokens] = useState("300000"), [cost, setCost] = useState("10");
  const [approved, setApproved] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const request = useRef({fingerprint: "", id: "", deadline: ""});
  async function submit(e: FormEvent) {
    e.preventDefault(); if (!approved || !defaults.data) return;
    setBusy(true); setError("");
    try {
      const scope: Data = { timezone, dateBasis: basis };
      if (startDate || endDate) Object.assign(scope, {startDate, endDate});
      if (variant) scope.variant = variant;
      if (cutoff) scope.evidenceCutoff = cutoff;
      const budget = {...defaults.data, maxModelCalls: Number(calls), maxTokens: Number(tokens), maxCostUsd: Number(cost), deadline: new Date(Date.now() + 30 * 60000).toISOString()};
      const fingerprint = JSON.stringify({publication, question, customer, scope, calls, tokens, cost, sourceMode});
      if (request.current.fingerprint !== fingerprint) request.current = {fingerprint, id: key(), deadline: budget.deadline};
      budget.deadline = request.current.deadline;
      const result = await api("ai/feedback/investigations", "POST", {requestId: request.current.id, publicationId: publication, text: question, sourceMode, scope, customerReference: customer, budget});
      setSelected(result.id); history.refresh();
    } catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  }
  if (selected) return <Workflow workflowId={selected} onBack={() => {setSelected(""); history.refresh();}} onPublished={() => {}} />;
  return <>
    <PageTitle eyebrow="Performance and investigation" title="Investigations" />
    <div className="two-columns">
      <section className="panel">
        <h2>Ask an investigation question</h2>
        <form onSubmit={submit}>
          <label className="field"><span>Published offer</span><select value={publication} onChange={e=>setPublication(e.target.value)}><option value="">Let the agents resolve the offer</option>{publication && !publications.data?.some(p=>p.id===publication) && <option value={publication}>{publication}</option>}{publications.data?.map(p=><option key={p.id} value={p.id}>{p.data.preview?.configuration?.offer?.name || p.id}</option>)}</select></label>
          <label className="field"><span>Your question</span><textarea required rows={5} maxLength={6000} value={question} onChange={e=>setQuestion(e.target.value)} placeholder="What was acceptance between two dates, and was DEMO-0001 eligible for this personalized auto loan?" /></label>
          <Field name="customerReference" label="Customer ID or reference (optional)" value={customer} onChange={setCustomer} />
          <div className="two-columns"><Field name="startDate" label="Start date (inclusive)" type="date" value={startDate} onChange={setStartDate}/><Field name="endDate" label="End date (inclusive)" type="date" value={endDate} onChange={setEndDate}/></div>
          <p className="muted">Leave dates blank to use dates in your question, or all available history when no period is requested.</p>
          <label className="field"><span>Date basis</span><select value={basis} onChange={e=>setBasis(e.target.value)}><option value="FIRST_ASSESSED">First assessment date</option><option value="FIRST_ELIGIBLE">First eligible date</option></select></label>
          <Field name="timezone" label="Timezone" value={timezone} onChange={setTimezone}/>
          <label className="field"><span>Observation source</span><select value={sourceMode} onChange={e=>setSourceMode(e.target.value)}><option value="DEMO">Recorded demo outcomes</option><option value="OBSERVED_REAL">Configured real-source outcomes</option></select></label>
          <label className="field"><span>Aggregate offer variant</span><select value={variant} onChange={e=>setVariant(e.target.value)}><option value="">Resolve from the question</option><option value="OFFER_FAMILY">Offer family</option><option value="ORIGINAL">Original terms</option><option value="PERSONALIZED">Exact personalized variant</option></select></label>
          <Field name="evidenceCutoff" label="Evidence cutoff (optional ISO instant)" value={cutoff} onChange={setCutoff}/>
          <h3>Investigation budget</h3>
          <div className="two-columns"><Field name="modelCalls" label="Maximum model calls" type="number" min={5} max={200} value={calls} onChange={setCalls}/><Field name="tokens" label="Maximum tokens" type="number" min={1000} value={tokens} onChange={setTokens}/></div>
          <Field name="maxCost" label="Maximum reserved cost ($)" type="number" min={0.01} step={0.01} value={cost} onChange={setCost}/>
          <p className="muted">30-minute execution window. Questions pause the clock while awaiting your answer. This investigation cannot change or publish offers.</p>
          <label className="check"><input type="checkbox" checked={approved} onChange={e=>setApproved(e.target.checked)}/>I authorize agent execution within this budget.</label>
          <Notice>{error || defaults.error || publications.error}</Notice>
          <button className="button primary" disabled={busy || !approved || !defaults.data}>{busy ? "Starting…" : "Start investigation"}</button>
        </form>
      </section>
      <section className="panel">
        <h2>Saved investigations</h2><Notice>{history.error}</Notice>
        {history.data?.length ? <div className="workflow-list">{history.data.map(w=><button key={w.id} onClick={()=>setSelected(w.id)}><span><b>{w.data.intent}</b><small>{date(w.data.createdAt)}</small></span><Badge>{human(w.data.state)}</Badge></button>)}</div> : <Empty title="No saved investigations">Ask a question to start the evidence review.</Empty>}
        <div className="pagination"><button className="button secondary" disabled={!after} onClick={()=>setAfter("")}>Newest page</button><button className="button secondary" disabled={history.data?.length!==20} onClick={()=>setAfter(history.data![19].id)}>Next page</button></div>
      </section>
    </div>
  </>;
}

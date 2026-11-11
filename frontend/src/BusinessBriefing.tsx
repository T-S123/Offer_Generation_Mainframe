import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, errorText, id, type Data } from "./api";
import { date, pct } from "./model";
import { Notice, PageTitle, useRemote } from "./ui";

/** Opens Network 2 on entry and renders the durable transcript and frozen comparison evidence. */
export function BusinessBriefing({ requestId, onRefresh }: {requestId: string; onRefresh: () => void}) {
  const [initial, setInitial] = useState<Data>();
  const [ack, setAck] = useState<Data>();
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const latest = useRemote<Data>(initial ? "ai/feedback/workflows/" + id(initial.id) : null, 2000);
  const bottom = useRef<HTMLDivElement>(null);
  useEffect(() => {
    let active = true;
    api<Data>("ai/feedback/briefings", "POST", {requestId}).then(
      response => { if (active) setInitial(response); },
      problem => { if (active) setError(errorText(problem)); },
    );
    return () => { active = false; };
  }, [requestId]);
  const view = [latest.data, ack, initial].filter(Boolean).sort((a,b) => b!.version - a!.version)[0];
  const workflow = view?.workflow;
  const question = workflow?.state === "NEEDS_INPUT" ? workflow.pendingQuestion : undefined;
  const turns = workflow?.clarificationRounds ?? 0;
  const thinking = !view || busy || workflow?.state === "FEEDBACK_PENDING";
  const canSend = !busy && (question || workflow?.state === "COMPLETED" && turns < 6);
  const evidence = workflow?.briefingEvidence;
  const messages: Data[] = workflow?.messages ?? [];
  useEffect(() => { bottom.current?.scrollIntoView?.({block:"nearest"}); }, [messages.length, question?.id]);

  async function send(event: FormEvent) {
    event.preventDefault();
    if (!canSend || !view || !draft.trim()) return;
    setBusy(true);
    setError("");
    try {
      const response = await api<Data>("ai/feedback/workflows/" + id(view.id) + "/messages", "POST", {
        expectedVersion: view.version, text: draft.trim(), ...(question ? {questionId: question.id} : {}),
      });
      setAck(response);
      setDraft("");
      latest.refresh();
    } catch (problem) {
      setError(errorText(problem));
    } finally {
      setBusy(false);
    }
  }

  return <>
    <PageTitle eyebrow="Network 2" title="Performance briefing"
      action={<button className="button secondary" disabled={busy} onClick={onRefresh}>Refresh briefing</button>} />
    <div className="briefing-layout">
      <section className="panel briefing-chat" aria-label="Performance conversation">
        <div className="briefing-transcript" role="log" aria-live="polite">
          {messages.map((message, index) => <div key={index} className={`chat-bubble ${message.role === "analyst" ? "customer" : "assistant"}`}>
            <strong>{message.role === "analyst" ? "You" : "Network 2"}</strong>
            <p className="preserve-lines">{message.text}</p>
            {message.news?.items?.length > 0 && <details><summary>Recent economic releases</summary>
              <ul>{message.news.items.map((item: Data) => <li key={item.evidenceId}>
                {item.publisher} · {date(item.publishedAt)} · <a href={item.url} target="_blank" rel="noreferrer">{item.title}</a>
              </li>)}</ul>
            </details>}
            {message.news?.sourcesReached === 0 && <p className="muted">Public economic feeds were unavailable for this reply.</p>}
          </div>)}
          {thinking && !error && <p role="status">Network 2 is reviewing the evidence…</p>}
          {question && !busy && <p role="status">Your input is needed</p>}
          <Notice>{error || latest.error || workflow?.error?.message || workflow?.outcome?.detail}</Notice>
          <div ref={bottom} />
        </div>
        {canSend && <form onSubmit={send} className="briefing-composer">
          {question?.question?.why && <p className="muted">{question.question.why}</p>}
          {question?.question?.choices?.length > 0 && <div className="assistant-choices">
            {question.question.choices.map((choice: string) => <button key={choice} type="button" className="button secondary"
              onClick={() => setDraft(choice)}>{choice}</button>)}
          </div>}
          <label className="field"><span>{question ? "Your reply" : "Ask about a trend or add economic context"}</span>
            <textarea rows={3} maxLength={6000} value={draft} onChange={event => setDraft(event.target.value)}/>
          </label>
          <button className="button primary" type="submit" disabled={!draft.trim()}>Send</button>
        </form>}
        {view && <small className="muted">{turns} of 6 exchanges used</small>}
      </section>
      {evidence?.offers?.length > 0 && <aside className="panel briefing-evidence" aria-label="Publication evidence">
        <h2>Published offers</h2>
        <p className="muted">{evidence.comparisonBasis}</p>
        {evidence.totalActive > evidence.offers.length && <p>Showing the newest {evidence.offers.length} active publications.</p>}
        {evidence.offers.map((offer: Data) => <details key={offer.id} open>
          <summary>{offer.name || offer.id}</summary>
          <p className="muted">{offer.id} · v{offer.version}</p>
          <p>Saved projection from {date(offer.projection.createdAt)}<br/>
            {pct(offer.projection.eligibilityPct)} eligibility · {pct(offer.projection.acceptancePct)} acceptance</p>
          {offer.recorded && <Observation label="Recorded customer outcomes" value={offer.recorded} />}
          {offer.native && <Observation label="Recorded local demo outcomes" value={offer.native} />}
          {offer.demo && <Observation label="Synthetic Presenter cohort" value={offer.demo} />}
          {!offer.recorded && !offer.native && !offer.demo && <p className="muted">Waiting for recorded outcomes.</p>}
        </details>)}
      </aside>}
    </div>
  </>;
}

/** Displays each observed denominator alongside the corresponding rate. */
function Observation({ label, value }: {label: string; value: Data}) {
  return <div className="briefing-observation">
    <strong>{label}</strong>
    <p>{pct(value.eligibilityPct)} eligibility{value.assessedCount != null && ` · ${value.E} / ${value.assessedCount} assessed`}<br/>
      {pct(value.acceptanceAmongEligiblePct)} acceptance · {value.Y} / {value.E} eligible selected</p>
    {value.coverage === "INCOMPLETE_DATA" && <small>Recorded coverage is incomplete. </small>}
    {value.maturity === "PROVISIONAL" && <small>Follow-up still open</small>}
    {value.unresolved > 0 && <small>{value.unresolved} profiles have unresolved outcomes.</small>}
  </div>;
}

import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { api, errorText, key } from "./api";
import { Modal, Notice } from "./ui";
import "./presenter.css";

type Action = "CLEAR" | "A2A";
type Prepared = { customerId: string; customerName: string; customerReference: string; profileVersion: number; draftId: string; draftName: string; draftVersion: number; catalogOfferId: string; catalogOfferName: string; discoverySeed: number; validationSeed: number };
type Job = { prepared?: Prepared; state: "IDLE" | "RUNNING" | "READY" | "FAILED"; action?: Action; requestId?: string; message?: string };
type Point = { x: number; y: number };
const actions = {
  CLEAR: { title: "Clear all data", details: "Remove demo customers, offers, selections, simulations, and agent history. Restore the default catalog and rules so you can create the first customer.", confirm: "Clear demo data" },
  A2A: { title: "Demo A2A", details: "Replace current demo data with the recording customer, approved policies, saved draft, and two fresh cohorts. Start on the landing page before publication.", confirm: "Prepare A2A demo" },
};
const positionKey = "lending.presenter.position";
const summaryKey = "lending.presenter.summaryAcknowledged";
const reloadWorkspace = () => window.location.reload();
const buttonSize = 52;

function bound({x, y}: Point): Point {
  return {
    x: Math.max(6, Math.min(x, window.innerWidth - buttonSize - 6)),
    y: Math.max(6, Math.min(y, window.innerHeight - buttonSize - 6)),
  };
}
function initialPosition(): Point {
  try {
    const saved = JSON.parse(localStorage.getItem(positionKey) || "null");
    if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) return bound(saved);
  } catch { /* Position storage is optional. */ }
  return bound({x: window.innerWidth - buttonSize - 20, y: window.innerHeight - buttonSize - 20});
}

/** Persistent presenter control with server-owned reset progress and an acknowledged summary of the exact prepared identities. */
export function Presenter({onComplete = reloadWorkspace}: {onComplete?: () => void}) {
  const [position, setPosition] = useState(initialPosition);
  const [open, setOpen] = useState(false);
  const [choice, setChoice] = useState<Action | null>(null);
  const [job, setJob] = useState<Job>();
  const [sending, setSending] = useState(false);
  const [commandError, setCommandError] = useState("");
  const [statusError, setStatusError] = useState("");
  const observed = useRef<Job | undefined>(undefined);
  const finished = useRef("");
  const pending = useRef<{action: Action; requestId: string} | null>(null);
  const posting = useRef(false);
  const drag = useRef<{id: number; start: Point; origin: Point; moved: boolean} | null>(null);
  const swallowClick = useRef(false);
  const button = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const resize = () => setPosition(previous => bound(previous));
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);
  function move(next: Point, save: boolean) {
    const point = bound(next);
    setPosition(point);
    if (save) {
      try { localStorage.setItem(positionKey, JSON.stringify(point)); } catch { /* Moving still works. */ }
    }
  }
  function pointerDown(e: PointerEvent<HTMLButtonElement>) {
    if (!e.isPrimary || e.button !== 0) return;
    swallowClick.current = false;
    drag.current = {id: e.pointerId, start: {x: e.clientX, y: e.clientY}, origin: position, moved: false};
    e.currentTarget.setPointerCapture(e.pointerId);
  }
  function pointerMove(e: PointerEvent<HTMLButtonElement>, end = false) {
    const d = drag.current;
    if (!d || d.id !== e.pointerId) return;
    const dx = e.clientX - d.start.x, dy = e.clientY - d.start.y;
    if (Math.hypot(dx,dy) > 5) d.moved = true;
    if (d.moved) move({x: d.origin.x + dx, y: d.origin.y + dy}, end);
    if (end) {
      swallowClick.current = d.moved;
      drag.current = null;
    }
  }
  function keyMove(e: KeyboardEvent<HTMLButtonElement>) {
    const directions: Record<string, Point> = {
      ArrowLeft: {x:-1,y:0}, ArrowRight: {x:1,y:0}, ArrowUp: {x:0,y:-1}, ArrowDown: {x:0,y:1},
    };
    const delta = directions[e.key];
    if (!delta) return;
    e.preventDefault();
    move({x: position.x + delta.x * 24, y: position.y + delta.y * 24}, true);
  }

  const receive = useCallback((next: Job) => {
    const previous = observed.current;
    observed.current = next;
    setJob(next);
    setStatusError("");
    if (!previous && next.state === "READY" && next.action === "A2A" && next.prepared) {
      let acknowledged = false;
      try { acknowledged = localStorage.getItem(summaryKey) === next.requestId; } catch { /* The summary still opens. */ }
      if (!acknowledged) setOpen(true);
    }
    if (next.state === "RUNNING") {
      pending.current = null;
      setChoice(null);
      setCommandError("");
    }
    if (next.state === "READY" && next.requestId && finished.current !== next.requestId &&
        (pending.current?.requestId === next.requestId ||
          (previous && (previous.state === "RUNNING" || previous.requestId !== next.requestId)))) {
      finished.current = next.requestId;
      pending.current = null;
      onComplete();
    }
    if (next.state === "FAILED" && next.requestId === pending.current?.requestId) {
      pending.current = null;
      setChoice(null);
    }
  }, [onComplete]);

  useEffect(() => {
    if (sending) return;
    let active = true;
    let reading = false;
    let timer = 0;
    async function poll() {
      if (reading || posting.current || !active) return;
      reading = true;
      window.clearTimeout(timer);
      try {
        // Share the initial session request with App so first-load requests use one cookie and CSRF token.
        if (!observed.current) await api("session");
        const next = await api<Job>("presenter/status");
        if (active && !posting.current) receive(next);
      } catch (err) {
        if (active) setStatusError(errorText(err));
      } finally {
        reading = false;
        if (active) timer = window.setTimeout(poll, observed.current?.state === "RUNNING" ? 1200 : open ? 5000 : 15000);
      }
    }
    void poll();
    window.addEventListener("focus", poll);
    return () => { active = false; window.clearTimeout(timer); window.removeEventListener("focus", poll); };
  }, [open, sending, receive]);

  function select(action: Action) {
    setChoice(action);
    setCommandError("");
    if (pending.current?.action !== action) pending.current = null;
  }
  async function confirm() {
    if (!choice || posting.current || job?.state === "RUNNING") return;
    posting.current = true;
    setSending(true);
    setCommandError("");
    const request = pending.current ?? {action: choice, requestId: key()};
    pending.current = request;
    try {
      receive(await api<Job>("presenter/reset", "POST", {...request, confirmed: true}));
    } catch (err) {
      setCommandError(errorText(err));
    } finally {
      posting.current = false;
      setSending(false);
    }
  }
  function close() {
    if (job?.state === "READY" && job.action === "A2A" && job.prepared && job.requestId) {
      try { localStorage.setItem(summaryKey, job.requestId); } catch { /* Summary remains available. */ }
    }
    setOpen(false);
    setChoice(null);
    button.current?.focus();
  }

  const busy = sending || job?.state === "RUNNING";
  const prepared = job?.state === "READY" && job.action === "A2A" ? job.prepared : undefined;
  return <>
    <button ref={button} type="button" className="presenter-toggle" style={{left: position.x, top: position.y}}
      aria-label="Presenter controls" aria-haspopup="dialog" aria-expanded={open}
      title="Presenter controls · Drag or use the arrow keys to move"
      onPointerDown={pointerDown} onPointerMove={e => pointerMove(e)} onPointerUp={e => pointerMove(e, true)}
      onPointerCancel={() => { drag.current = null; }}
      onKeyDown={keyMove} onClick={e => {
        if (swallowClick.current && e.detail !== 0) { swallowClick.current = false; return; }
        setOpen(true);
      }}>
      <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" strokeLinecap="round">
        <path d="M3 4h18M5 4v12h14V4M12 16v3m-4 2 4-2 4 2"/>
        <path d="m10 7 5 3-5 3Z" fill="currentColor" stroke="none"/>
      </svg>
      {busy && <span className="presenter-active" />}
    </button>
    {open && <Modal title={busy ? "Preparing demo" : choice ? actions[choice].title : prepared ? "A2A demo ready" : "Presenter controls"} onClose={close}>
      <Notice>{statusError}</Notice>
      <Notice>{commandError}</Notice>
      {!choice && job?.state === "FAILED" && <Notice>{job.message || "Demo preparation failed. Retry when the services are ready."}</Notice>}
      {busy ? <div role="status" className="presenter-progress">
        <progress aria-label="Demo preparation in progress" />
        <p>{job?.state === "RUNNING" && job.message || "Starting demo preparation…"}</p>
      </div> : choice ? <>
        <p>{actions[choice].details}</p>
        <p>Everyone using this local workspace will return to the landing page.</p>
        <div className="form-actions">
          <button type="button" className="button secondary" onClick={() => setChoice(null)}>Back</button>
          <button type="button" className="button primary" disabled={!job || !!statusError} onClick={confirm}>{actions[choice].confirm}</button>
        </div>
      </> : <>
        {prepared && <PreparedSummary data={prepared} onDone={close}/>}
        <div className="presenter-actions">
        {(Object.keys(actions) as Action[]).map(action => <button type="button" key={action}
          className="button secondary" disabled={!job || !!statusError} onClick={() => select(action)}>
          {actions[action].title}<span aria-hidden="true">›</span>
        </button>)}
      </div></>}
    </Modal>}
  </>;
}

/** Shows server-reported fixture identities without implying that a draft or catalog baseline is an AI publication. */
function PreparedSummary({data, onDone}: {data: Prepared; onDone: () => void}) {
  return <section className="presenter-summary" aria-label="Prepared A2A demo details">
    <h3>Customer created</h3>
    <dl>
      <dt>Name</dt><dd>{data.customerName}</dd>
      <dt>Reference</dt><dd><code>{data.customerReference}</code></dd>
      <dt>Customer ID</dt><dd><code>{data.customerId}</code></dd>
      <dt>Saved profile</dt><dd>Version {data.profileVersion}</dd>
    </dl>
    <h3>Offer draft created · Network 1</h3>
    <dl>
      <dt>Draft</dt><dd>{data.draftName} · v{data.draftVersion}</dd>
      <dt>Draft ID</dt><dd><code>{data.draftId}</code></dd>
      <dt>Discovery cohort seed</dt><dd>{data.discoverySeed}</dd>
      <dt>Validation cohort seed</dt><dd>{data.validationSeed}</dd>
    </dl>
    <h3>Starting catalog offer</h3>
    <p>{data.catalogOfferName} · <code>{data.catalogOfferId}</code></p>
    <p>Enter Business workspace → A2A exploration to test and publish the saved draft with Network 1.
      Then log in as {data.customerName} for Network 3. Use customer reference <code>{data.customerReference}</code> in Network 2 investigations.</p>
    <p>Preparation has not published an AI offer or created a customer-specific variant. Those are created during the Network 1 and Network 3 demos.</p>
    <button type="button" className="button primary" onClick={onDone}>Done</button>
  </section>;
}

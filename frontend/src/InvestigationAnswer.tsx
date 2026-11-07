import { id, type Data } from "./api";
import { date, human, number, pct } from "./model";
import { Badge, Evidence, Notice, Stat } from "./ui";

/** Presents server-computed figures and exact historical evidence; browser code never calculates rates or decides eligibility. */
export function InvestigationAnswer({answer, onEvidence}: {answer: Data; onEvidence: (ref:string)=>void}) {
  const result=answer.measurement?.result, comparison=result?.comparison, observed=comparison?.observed, scope=answer.scope ?? {}, trace=answer.customerTrace?.result;
  return <section className="panel">
    <div className="panel-top"><h2>Investigation answer</h2><Badge>{human(comparison?.comparability || "Unavailable")}</Badge></div>
    <p className="preserve-lines">{answer.summary}</p>
    <p className="muted">{scope.startDate ? `${scope.startDate} through ${scope.endDate}` : "All recorded history"} · {human(scope.dateBasis)} · {scope.timezone} · {human(scope.sourceMode)} · offer version {scope.offerVersion} · {human(scope.variant)}</p>
    <p className="muted">Evidence cutoff: {date(scope.evidenceCutoff)}</p>
    {observed && <>
      <div className="stats-grid">
        <Stat label="Recorded acceptance" value={pct(observed.acceptanceAmongEligiblePct)} detail={`${number(observed.Y)} selected / ${number(observed.E)} eligible`}/>
        <Stat label="Recorded eligibility" value={pct(observed.eligibilityPct)} detail={`${number(observed.E)} eligible / ${observed.assessedCount == null ? "not recorded" : number(observed.assessedCount)} assessed`}/>
        <Stat label="Frozen predicted acceptance" value={pct(result.originalPrediction?.estimatePct ?? comparison.prediction?.estimatePct)} detail={human(result.originalPrediction?.predictionKind ?? comparison.prediction?.predictionKind)}/>
        <Stat label="Frozen predicted eligibility" value={pct(result.eligibilityComparison?.predictedPct)} detail="Synthetic validation cohort; diagnostic comparison"/>
      </div>
      <p>Combined counts are used across the period. Follow-up: {observed.followUpDays} days; {number(observed.matureEnrollments)} mature and {number(observed.provisionalEnrollments)} provisional enrollments.</p>
      {comparison.comparability === "MATCH" && comparison.gapPercentagePoints != null ? <p>Validated forecast gap: {number(comparison.gapPercentagePoints)} percentage points.</p> : <Notice tone="info">These figures do not establish a validated forecast error. {human(comparison.comparability)}.</Notice>}
      {result.sourceRefresh?.status === "SOURCE_UNAVAILABLE" && <Notice tone="info">The outcome source is unavailable. These figures use previously recorded evidence.</Notice>}
      {observed.limitations?.map((line:string)=><p className="muted" key={line}>{line}</p>)}
    </>}
    {trace && trace.status !== "NOT_REQUESTED" && <div className="question-panel">
      <h3>Customer eligibility: {trace.customerReference}</h3><Badge>{human(trace.eligibility)}</Badge>
      <p>Published offer {trace.publicationId}, version {trace.offerVersion}; {human(trace.variant)}.</p>
      <p className="muted">Customer decision period: {trace.definition?.scope?.startDate ? `${trace.definition.scope.startDate} through ${trace.definition.scope.endDate}` : "all recorded history at the evidence cutoff"}.</p>
      <p>Profile version: {trace.profileVersion ?? "Not recorded"} · Rule version: {trace.policyVersion || "Not recorded"} · Qualification: {trace.qualificationId || "Not recorded"}</p>
      {trace.eligibility === "NOT_RECORDED" && <p>No exact decision is recorded. This does not mean the customer was ineligible.</p>}
      {trace.limitations?.map((line:string)=><p className="muted" key={line}>{line}</p>)}
    </div>}
    <h3>Supporting evidence</h3>
    {answer.measurement?.snapshotRef && <button className="button secondary" onClick={()=>onEvidence(answer.measurement.snapshotRef)}>Open measurement evidence</button>}{" "}
    {answer.customerTrace?.snapshotRef && <button className="button secondary" onClick={()=>onEvidence(answer.customerTrace.snapshotRef)}>Open customer decision evidence</button>}
    {(observed?.evidenceIds?.length > 0 || trace?.evidenceIds?.length > 0) && <details className="evidence"><summary>Open supporting source records</summary><ul>{[...new Set<string>([...(observed?.evidenceIds ?? []), ...(trace?.evidenceIds ?? [])])].slice(0,30).map(ref=><li key={ref}><a target="_blank" rel="noreferrer" href={`/web-api/ai/performance/publications/${id(observed?.publicationId ?? trace.publicationId)}/records/${id(ref)}`}>{ref}</a></li>)}</ul><p className="muted">Up to 30 links shown. The full snapshot retains the evidence count and recorded references.</p></details>}
    <p className="muted">Reviewer: {answer.review}</p>
    <Evidence value={answer} label="Inspect measurement definitions and source references" />
  </section>;
}

import {useState} from "react";
import {api,errorText,id,type Data} from "./api";
import {Badge,Notice,Stat,useRemote} from "./ui";
import {human,money,pct,date} from "./model";

/** Customer-controlled personalization with plain labels and structured guidance; calculations, issuance and acceptance stay in owner APIs. */
export function Personalization({profileVersion,onReview}:{profileVersion:number;onReview:(offerId:string)=>void}){
  const status=useRemote<Data>(`customer/personalization?profileVersion=${profileVersion}`,3000);
  const [busy,setBusy]=useState(false),[error,setError]=useState(""),[opened,setOpened]=useState(false),[reply,setReply]=useState("");
  const data=status.data,state=data?.state??"NOT_STARTED",offer=data?.offer;
  const running=["PERSONALIZATION_PENDING","NEEDS_INPUT"].includes(state);
  async function command(action:"start"|"cancel"|"reply"){
    setBusy(true);setError("");try{
      if(action==="start")await api("customer/personalization","POST",{profileVersion});
      if(action==="cancel")await api(`customer/personalization/${id(data!.id)}/cancel`,"POST",{});
      if(action==="reply")await api(`customer/personalization/${id(data!.id)}/messages`,"POST",{expectedVersion:data!.version,questionId:data!.pendingQuestion.id,text:reply});
      if(action==="reply")setReply("");status.refresh();
    }catch(e){setError(errorText(e));}finally{setBusy(false);}
  }
  return <section className="panel">
    <div className="panel-top"><div><span className="eyebrow">Personalization and guidance</span><h2>Personalized offer</h2></div><Badge>{human(state)}</Badge></div>
    <p>Saved profile v{profileVersion}</p>
    <Notice>{error || status.error || data?.error?.message}</Notice>
    {state==="NOT_STARTED"&&<><p className="muted">Starts only when you choose. Limit: {data?.limits?.maxCandidates??3} candidates, {data?.limits?.maxModelCalls??10} model calls, up to ${data?.limits?.maxCostUsd??15}, within {data?.limits?.timeoutSeconds??180} seconds. Actual usage may be lower.</p><button className="button primary" disabled={busy||!data} onClick={()=>command("start")}>Find my personalized offer</button></>}
    {data?.summary&&<p className="preserve-lines">{data.summary}</p>}
    {running&&<button className="button secondary" disabled={busy} onClick={()=>command("cancel")}>Cancel personalization</button>}
    {data?.pendingQuestion&&<div className="question-panel"><h3>{data.pendingQuestion.question.prompt}</h3><p>{data.pendingQuestion.question.why}</p><label className="field"><span>Your answer</span><textarea rows={3} value={reply} onChange={e=>setReply(e.target.value)}/></label><button className="button primary" disabled={busy||!reply.trim()} onClick={()=>command("reply")}>Send answer</button></div>}
    {state==="COMPLETED"&&offer?.personalized&&<>
      <ol><li>Open your qualified offer.</li><li>Review the current terms and cost.</li><li>Explicitly confirm your choice.</li></ol>
      {!opened?<button className="button primary" onClick={()=>setOpened(true)}>Open personalized offer</button>:<div className="question-panel">
        <h3>{human(offer.product)}</h3><div className="stats-grid"><Stat label="Amount" value={money(offer.personalized.terms.amountUsd)}/><Stat label="APR" value={pct(offer.personalized.terms.aprPct)}/><Stat label="Monthly payment" value={money(offer.personalized.metrics.monthlyPaymentUsd)}/><Stat label="Borrowing cost" value={money(offer.personalized.metrics.totalCostUsd)}/></div>
        <p>Term: {offer.personalized.terms.termMonths} months · Annual fee: {money(offer.personalized.terms.annualFeeUsd)} · Valid until {date(offer.personalized.validUntil)}</p>
        <p>These are individually qualified terms, not a population acceptance forecast. The offer is rechecked before your selection is saved.</p>
        <button className="button primary" onClick={()=>onReview(offer.id)}>Review current terms</button>
      </div>}
    </>}
    {["FAILED","BLOCKED","PAUSED_BUDGET","CANCELED","STALE_PROFILE","OFFER_CHANGED","NO_QUALIFIED_IMPROVEMENT"].includes(state)&&<p>Your currently valid offers remain available. Saving updated information allows a new personalization request.</p>}
  </section>;
}

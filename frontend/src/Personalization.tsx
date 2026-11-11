import {useEffect,useRef,useState} from "react";
import {api,errorText,id,type Data} from "./api";
import {Badge,Notice,Stat,useRemote} from "./ui";
import {human,money,pct,date} from "./model";

/** Session-bound chat opens when updated offers can be discussed, exposes editable suggestions, and suppresses stale polls during resume. */
export function Personalization({profileVersion,onReview,readyForConversation=true}:{profileVersion:number;onReview:(offerId:string)=>void;readyForConversation?:boolean}){
  const status=useRemote<Data>(`customer/personalization?profileVersion=${profileVersion}`,3000);
  const [ack,setAck]=useState<Data>(),[busy,setBusy]=useState(false),[error,setError]=useState(""),[opened,setOpened]=useState(false),[reply,setReply]=useState("");
  const autoAttempt=useRef(false),autoOpened=useRef(false),end=useRef<HTMLDivElement>(null);
  const data=ack&&(!status.data?.id||ack.id===status.data.id)&&(ack.version??0)>=(status.data?.version??0)?ack:status.data;
  const state=data?.state??"NOT_STARTED",question=state==="NEEDS_INPUT"?data?.pendingQuestion:undefined;
  const running=["PERSONALIZATION_PENDING","NEEDS_INPUT"].includes(state),thinking=state==="PERSONALIZATION_PENDING"||busy;
  const messages:Data[]=data?.messages??[];
  async function command(action:"start"|"cancel"|"reply",automatic=false){
    setBusy(true);setError("");try{
      let result:Data;
      if(action==="start")result=await api("customer/personalization","POST",{profileVersion,...(!automatic&&reply.trim()?{message:reply.trim()}: {})});
      else if(action==="cancel")result=await api(`customer/personalization/${id(data!.id)}/cancel`,"POST",{});
      else result=await api(`customer/personalization/${id(data!.id)}/messages`,"POST",{expectedVersion:data!.version,questionId:question!.id,text:reply.trim()});
      setAck(result);if(action!=="cancel")setReply("");status.refresh();
    }catch(e){setError(errorText(e));}finally{setBusy(false);}
  }
  useEffect(()=>{
    if(!status.data?.autoStart||!readyForConversation)return;
    if(!autoOpened.current){autoOpened.current=true;setOpened(true);}
    if(status.data.state==="NOT_STARTED"&&!autoAttempt.current){autoAttempt.current=true;void command("start",true);}
  },[status.data?.autoStart,status.data?.state,readyForConversation]);
  useEffect(()=>{end.current?.scrollIntoView?.({block:"nearest"});},[messages.length,question?.id,opened]);
  const label=question?"Your reply is needed":thinking?"Thinking about your offers":state==="COMPLETED"?"Your personalized offer is ready":"Your offer assistant";
  return <aside className="offer-assistant" aria-label="Personalized offer assistant">
    {!opened?<button className="assistant-launcher" onClick={()=>setOpened(true)} aria-expanded={false}><span aria-hidden="true">✦</span><span>{label}</span>{question&&<span className="assistant-dot"/>}</button>:
    <section className="assistant-window">
      <header className="assistant-header"><div><strong>Your offer assistant</strong><small>{label}</small></div><button aria-label="Minimize offer assistant" className="text-button" onClick={()=>setOpened(false)}>−</button></header>
      <div className="assistant-transcript" role="log" aria-live="polite">
        {state==="NOT_STARTED"&&<p className="chat-bubble assistant">Tell me what you would like to improve about your available offers. We can explore the tradeoffs together.</p>}
        {messages.map((m,i)=><div key={m.questionId?`${m.role}-${m.questionId}`:i} className={`chat-bubble ${m.role==="customer"?"customer":"assistant"}`}><small>{m.role==="customer"?"You":"Offer assistant"}</small><p className="preserve-lines">{m.text}</p></div>)}
        {question&&!messages.some(m=>m.questionId===question.id&&m.role==="assistant")&&<p className="chat-bubble assistant preserve-lines">{question.question.prompt}</p>}
        {question?.question.choices?.length>0&&!busy&&<div className="assistant-choices" aria-label="Suggested replies">{question.question.choices.map((choice:string)=><button className="button secondary" key={choice} onClick={()=>setReply(choice)}>{choice}</button>)}</div>}
        {data?.summary&&messages.at(-1)?.text!==data.summary&&<p className="chat-bubble assistant preserve-lines">{data.summary}</p>}
        {data?.assumptions?.length>0&&<div className="chat-bubble assistant"><strong>Assumptions used</strong><ul>{data!.assumptions.map((a:string)=><li key={a}>{a}</li>)}</ul></div>}
        {thinking&&<p className="assistant-thinking" role="status"><span className="thinking-dots" aria-hidden="true">● ● ●</span> {busy?"Sending…":"Comparing offers and refining your options…"}</p>}
        {state==="COMPLETED"&&<button className="button primary" onClick={()=>{onReview(data!.quote.id);setOpened(false);}}>See personalized offer in My offers</button>}
        <Notice>{error||status.error||data?.error?.message}</Notice><div ref={end}/>
      </div>
      <footer className="assistant-footer">
        {question&&!busy&&<><p className="muted">{question.question.why}</p><small>Exchange {question.round??data?.conversation?.roundsUsed} of 6</small></>}
        {!busy&&(question||state==="NOT_STARTED")&&<form onSubmit={e=>{e.preventDefault();void command(question?"reply":"start");}}><label className="field"><span>{question?"Your reply":"What would make an offer better for you?"}</span><textarea rows={3} maxLength={6000} value={reply} onChange={e=>setReply(e.target.value)}/></label><button className="button primary" disabled={!reply.trim()||!data} type="submit">Send</button></form>}
        {running&&<button className="text-button" disabled={busy} onClick={()=>command("cancel")}>End conversation</button>}
        <small>Up to 6 exchanges. You choose whether to select an offer. Unselected personalized offers expire when you sign out.</small>
      </footer>
    </section>}
  </aside>;
}

/** Presents ephemeral A2A quotes beside standard offers and retained selections across later login sessions. */
export function PersonalizedOffers({onReview,revision=0}:{onReview:(id:string)=>void;revision?:number}){
  const quotes=useRemote<Data>("customer/session-offers",3000);
  useEffect(()=>quotes.refresh(),[revision]);
  return <><Notice>{quotes.error}</Notice><div className="offer-grid">{quotes.data?.items?.map((q:Data)=>{
    const offer=q.offer,option=offer.personalized;
    return <section className="panel offer-card session-offer" key={q.id}>
      <div className="panel-top"><div><span className="eyebrow">Created just for you · {human(offer.product)}</span><h2>Personalized offer</h2></div><Badge tone="good">{q.status==="SELECTED"?"Selected personalized offer":"Personalized · this session"}</Badge></div>
      <div className="stats-grid"><Stat label="Amount" value={money(option.terms.amountUsd)}/><Stat label="APR" value={pct(option.terms.aprPct)}/><Stat label="Monthly payment" value={money(option.metrics.monthlyPaymentUsd)}/><Stat label="Borrowing cost" value={money(option.metrics.totalCostUsd)}/></div>
      <p className="muted">Based on {offer.catalogOfferId} · Saved profile v{q.profileVersion}</p>
      <p>{option.terms.termMonths} months · Annual fee {money(option.terms.annualFeeUsd)}</p>
      {q.status==="SELECTED"?<p>Your selection is saved. This demo records a choice; it does not fund a loan.</p>:<><p>Available until sign-out, session expiry or qualification expiry ({date(option.validUntil)}). Not part of the shared catalog.</p><button className="button primary" onClick={()=>onReview(q.id)}>Review personalized offer</button></>}
      <p className="muted">Individually qualified. Fit scores use simulated utility; observed acceptance, population eligibility and bank-profit superiority have not been established.</p>
    </section>;
  })}</div></>;
}

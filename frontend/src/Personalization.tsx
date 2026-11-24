import {useEffect,useId,useRef,useState} from "react";
import {api,errorText,id,type Data} from "./api";
import {Badge,Icon,Notice,Stat,useRemote} from "./ui";
import {human,money,pct,date} from "./model";

/** Shows the session's customer-facing messages in a scrollable chat with a separate reply area. */
export function Personalization({profileVersion,onReview,readyForConversation=true}:{profileVersion:number;onReview:(offerId:string)=>void;readyForConversation?:boolean}){
  const status=useRemote<Data>(`customer/personalization?profileVersion=${profileVersion}`,3000);
  const [ack,setAck]=useState<Data>(),[busy,setBusy]=useState(false),[error,setError]=useState(""),[opened,setOpened]=useState(false),[reply,setReply]=useState("");
  const panelId=useId();
  const autoAttempt=useRef(false),autoOpened=useRef(false);
  const transcript=useRef<HTMLDivElement>(null),composer=useRef<HTMLTextAreaElement>(null);
  const launcher=useRef<HTMLButtonElement>(null),minimize=useRef<HTMLButtonElement>(null);
  const followLatest=useRef(true),focusOnToggle=useRef(false);
  const data=ack&&(!status.data?.id||ack.id===status.data.id)&&(ack.version??0)>=(status.data?.version??0)?ack:status.data;
  const state=data?.state??"NOT_STARTED",question=state==="NEEDS_INPUT"?data?.pendingQuestion:undefined;
  const running=["PERSONALIZATION_PENDING","NEEDS_INPUT"].includes(state);
  const canReply=!!data&&(state==="NOT_STARTED"||!!question);
  const messages:Data[]=(data?.messages??[]).filter((message:Data)=>
    ["assistant","customer"].includes(message.role)&&typeof message.text==="string"&&message.text.trim());
  const lastMessage=messages.at(-1);
  const pendingMessage=question&&!messages.some(message=>message.questionId===question.id&&message.role==="assistant")
    ? question.question.prompt : "";
  const outcome=state==="CANCELED"?"Conversation ended.":
    ["COMPLETED","NO_QUALIFIED_IMPROVEMENT","OFFER_CHANGED","STALE_PROFILE"].includes(state)?data?.summary||data?.message:"";
  const finalMessage=lastMessage?.role==="assistant"&&lastMessage.text===outcome?"":outcome;
  const visibleError=error||status.error||(data?.error?"I couldn't finish reviewing your offers. Your current offers are still available.":"");
  const statusText=busy&&canReply?"Sending…":busy||state==="PERSONALIZATION_PENDING"?"Thinking about your offers":
    question?"Your reply is needed":!data&&!visibleError?"Connecting…":"";
  const launcherLabel=statusText||(state==="COMPLETED"?"Your personalized offer is ready":"Your offer assistant");

  async function command(action:"start"|"cancel"|"reply",automatic=false){
    setBusy(true);setError("");try{
      let result:Data;
      if(action==="start")result=await api("customer/personalization","POST",{profileVersion,...(!automatic&&reply.trim()?{message:reply.trim()}: {})});
      else if(action==="cancel")result=await api(`customer/personalization/${id(data!.id)}/cancel`,"POST",{});
      else result=await api(`customer/personalization/${id(data!.id)}/messages`,"POST",{expectedVersion:data!.version,questionId:question!.id,text:reply.trim()});
      setAck(result);if(action!=="cancel")setReply("");status.refresh();
    }catch(e){setError(errorText(e));}finally{setBusy(false);}
  }

  function toggleChat(nextOpen:boolean){
    followLatest.current=true;
    focusOnToggle.current=true;
    setOpened(nextOpen);
  }

  useEffect(()=>{
    if(!status.data?.autoStart||!readyForConversation)return;
    if(!autoOpened.current){autoOpened.current=true;setOpened(true);}
    if(status.data.state==="NOT_STARTED"&&!autoAttempt.current){autoAttempt.current=true;void command("start",true);}
  },[status.data?.autoStart,status.data?.state,readyForConversation]);
  useEffect(()=>{
    const log=transcript.current;
    if(log&&followLatest.current)log.scrollTop=log.scrollHeight;
  },[opened,messages.length,lastMessage?.text,pendingMessage,finalMessage,state,busy,visibleError]);
  useEffect(()=>{
    if(!focusOnToggle.current)return;
    focusOnToggle.current=false;
    (opened?minimize.current:launcher.current)?.focus();
  },[opened]);

  return <aside className={`offer-assistant${opened?" is-open":""}`} aria-label="Personalized offer assistant">
    {!opened?<button type="button" ref={launcher} className="assistant-launcher" onClick={()=>toggleChat(true)} aria-expanded={false}>
      <Icon name="spark" /><span>{launcherLabel}</span>
    </button>:
      <section className="assistant-window" aria-labelledby={panelId} onKeyDown={event=>{
        if(event.key==="Escape"&&!event.nativeEvent.isComposing)toggleChat(false);
      }}>
        <header className="assistant-header">
          <span className="assistant-mark"><Icon name="spark" /></span>
          <h2 id={panelId}>Your offer assistant</h2>
          <button type="button" ref={minimize} className="assistant-minimize" aria-label="Minimize offer assistant" onClick={()=>toggleChat(false)}>−</button>
        </header>
        <div className="assistant-transcript" ref={transcript} role="log" aria-label="Conversation" tabIndex={0} onScroll={event=>{
          const log=event.currentTarget;
          followLatest.current=log.scrollHeight-log.scrollTop-log.clientHeight<48;
        }}>
          {state==="NOT_STARTED"&&<AssistantMessage text="What would you like to improve about your offers?" />}
          {messages.map((message,i)=><AssistantMessage key={message.questionId?`${message.role}-${message.questionId}`:i} text={message.text} customer={message.role==="customer"} />)}
          {pendingMessage&&<AssistantMessage text={pendingMessage} />}
          {question?.question.choices?.length>0&&!busy&&<div className="assistant-choices" aria-label="Suggested replies">
            {question.question.choices.map((choice:string)=><button className="button secondary" type="button" key={choice} onClick={()=>{
              setReply(choice);
              composer.current?.focus();
            }}>{choice}</button>)}
          </div>}
          {finalMessage&&<AssistantMessage text={finalMessage} />}
          <Notice>{visibleError}</Notice>
        </div>
        {(statusText||running||canReply||data?.quote?.id&&state==="COMPLETED")&&<footer className="assistant-footer">
          {(statusText||running)&&<div className="assistant-actions">
            {statusText&&<span role="status">{statusText}</span>}
            {running&&<button className="text-button" type="button" disabled={busy} onClick={()=>void command("cancel")}>End conversation</button>}
          </div>}
          {canReply&&!busy&&<form className="assistant-composer" onSubmit={event=>{
            event.preventDefault();
            if(!reply.trim())return;
            followLatest.current=true;
            void command(question?"reply":"start");
          }}>
            <textarea ref={composer} aria-label={question?"Your reply":"Your message"} placeholder={question?"Type your reply…":"Type your message…"} rows={2} maxLength={6000} value={reply} onChange={event=>setReply(event.target.value)} />
            <button className="button primary" disabled={!reply.trim()} type="submit">Send</button>
          </form>}
          {state==="COMPLETED"&&data?.quote?.id&&<button className="button primary assistant-review" type="button" onClick={()=>{
            onReview(data.quote.id);
            setOpened(false);
          }}>See personalized offer in My offers <Icon /></button>}
        </footer>}
      </section>}
  </aside>;
}

function AssistantMessage({text,customer=false}:{text:string;customer?:boolean}){
  return <div className={`assistant-message${customer?" from-customer":""}`}>
    <span>{customer?"You":"Offer assistant"}</span>
    <p className="preserve-lines">{text}</p>
  </div>;
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

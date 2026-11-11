// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from "vitest";
import {cleanup,fireEvent,render,screen,waitFor} from "@testing-library/react";
import {Personalization,PersonalizedOffers} from "./Personalization";
const request=vi.hoisted(()=>vi.fn());vi.mock("./api",async()=>({...await vi.importActual("./api"),api:request}));beforeEach(()=>request.mockReset());afterEach(cleanup);
it("allows manual conversation with an actual initial message without starting on initial signup",async()=>{
  request.mockResolvedValue({state:"NOT_STARTED",limits:{maxCostUsd:15}});render(<Personalization profileVersion={1} onReview={vi.fn()}/>);
  fireEvent.click(await screen.findByRole("button",{name:/Your offer assistant/}));
  expect(request.mock.calls.every(c=>c[1]!=="POST")).toBe(true);
  fireEvent.change(screen.getByRole("textbox"),{target:{value:"I want a more manageable monthly payment."}});fireEvent.click(screen.getByRole("button",{name:"Send"}));
  await waitFor(()=>expect(request).toHaveBeenCalledWith("customer/personalization","POST",{profileVersion:1,message:"I want a more manageable monthly payment."}));
});
it("starts once after a profile update only when qualification finishes",async()=>{
  request.mockImplementation((_p,m)=>Promise.resolve(m==="POST"?{id:"run",version:1,state:"PERSONALIZATION_PENDING"}:{state:"NOT_STARTED",autoStart:true}));
  const view=render(<Personalization profileVersion={3} pipelineComplete={false} onReview={vi.fn()}/>);
  await waitFor(()=>expect(request).toHaveBeenCalled());expect(request.mock.calls.filter(c=>c[1]==="POST")).toHaveLength(0);
  view.rerender(<Personalization profileVersion={3} pipelineComplete onReview={vi.fn()}/>);
  await waitFor(()=>expect(request.mock.calls.filter(c=>c[1]==="POST")).toHaveLength(1));expect(screen.getByRole("status").textContent).toContain("Comparing offers");
});
it("hides the answer box after Send and ignores an older poll while retaining the transcript",async()=>{
  const pending={id:"run",version:5,state:"NEEDS_INPUT",pendingQuestion:{id:"q1",round:1,question:{prompt:"Which payment range works for you?",why:"This changes the term."}},messages:[{role:"assistant",questionId:"q1",text:"Which payment range works for you?"}]};
  request.mockImplementation((_p,m)=>Promise.resolve(m==="POST"?{...pending,version:6,state:"PERSONALIZATION_PENDING",pendingQuestion:null,messages:[...pending.messages,{role:"customer",text:"Under $300."}]}:pending));
  render(<Personalization profileVersion={3} onReview={vi.fn()}/>);fireEvent.click(await screen.findByRole("button",{name:/Your reply is needed/}));
  fireEvent.change(screen.getByRole("textbox"),{target:{value:"Under $300."}});fireEvent.click(screen.getByRole("button",{name:"Send"}));
  await screen.findByText("Under $300.");expect(screen.queryByRole("textbox")).toBeNull();expect(screen.getByRole("status").textContent).toContain("Comparing offers");
  expect(request).toHaveBeenCalledWith("customer/personalization/run/messages","POST",{expectedVersion:5,questionId:"q1",text:"Under $300."});
});
it("keeps failed answers editable for deliberate retry",async()=>{
  request.mockImplementation((_p,m)=>m==="POST"?Promise.reject(new Error("Connection interrupted")):Promise.resolve({id:"run",version:2,state:"NEEDS_INPUT",pendingQuestion:{id:"q",question:{prompt:"What matters most?",why:"Choose a tradeoff"}}}));
  render(<Personalization profileVersion={3} onReview={vi.fn()}/>);fireEvent.click(await screen.findByRole("button",{name:/Your reply is needed/}));fireEvent.change(screen.getByRole("textbox"),{target:{value:"Total cost"}});fireEvent.click(screen.getByRole("button",{name:"Send"}));await screen.findByText("Connection interrupted");expect((screen.getByRole("textbox") as HTMLTextAreaElement).value).toBe("Total cost");
});
it("shows model suggestions as editable answers without sending automatically",async()=>{
  request.mockResolvedValue({id:"run",version:2,state:"NEEDS_INPUT",pendingQuestion:{id:"q",question:{prompt:"Which tradeoff works for you?",why:"This changes the candidate.",choices:["Lower monthly payment","Lower total cost"]}}});
  render(<Personalization profileVersion={3} onReview={vi.fn()}/>);fireEvent.click(await screen.findByRole("button",{name:/Your reply is needed/}));fireEvent.click(screen.getByRole("button",{name:"Lower total cost"}));
  expect((screen.getByRole("textbox") as HTMLTextAreaElement).value).toBe("Lower total cost");expect(request.mock.calls.every(c=>c[1]!=="POST")).toBe(true);
});
it("navigates to My offers without selecting and labels temporary and retained offers",async()=>{
  const option={terms:{amountUsd:10000,aprPct:8,annualFeeUsd:0,termMonths:36},metrics:{monthlyPaymentUsd:313,totalCostUsd:1268}};
  request.mockImplementation(p=>Promise.resolve((p??"").includes("session-offers")?{items:[{id:"quote",status:"AVAILABLE",offer:{product:"PERSONAL_LOAN",personalized:option}},{id:"saved",status:"SELECTED",offer:{product:"AUTO_LOAN",personalized:option}}]}:{state:"COMPLETED",quote:{id:"quote"}}));
  const review=vi.fn();render(<><Personalization profileVersion={3} onReview={review}/><PersonalizedOffers onReview={review}/></>);
  fireEvent.click(await screen.findByRole("button",{name:/Your personalized offer is ready/}));fireEvent.click(screen.getByRole("button",{name:"See personalized offer in My offers"}));expect(review).toHaveBeenCalledWith("quote");await screen.findByText("Personalized · this session");expect(screen.getByText("Selected personalized offer")).toBeTruthy();expect(request.mock.calls.every(c=>c[1]!=="POST")).toBe(true);
});

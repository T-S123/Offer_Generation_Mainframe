// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Investigations } from "./Investigations";
import { InvestigationAnswer } from "./InvestigationAnswer";
const request=vi.hoisted(()=>vi.fn());
vi.mock("./api",async()=>({...await vi.importActual("./api"),api:request}));
beforeEach(()=>request.mockReset());afterEach(cleanup);
const scope={startDate:"2026-09-01",endDate:"2026-09-02",timezone:"America/Los_Angeles",dateBasis:"FIRST_ASSESSED",sourceMode:"DEMO",offerVersion:1,variant:"OFFER_FAMILY",evidenceCutoff:"2026-09-30T12:00:00Z"};
const answer={scope,summary:"Recorded evidence reviewed.",review:"Exact offer and cohort checked.",measurement:{snapshotRef:"sha256:measurement",result:{eligibilityComparison:{predictedPct:90,observedPct:null},comparison:{prediction:{estimatePct:80,predictionKind:"SIMULATED_UTILITY"},comparability:"TARGET_MISMATCH",gapPercentagePoints:null,observed:{E:10,Y:4,assessedCount:null,eligibilityPct:null,acceptanceAmongEligiblePct:40,followUpDays:14,matureEnrollments:10,provisionalEnrollments:0,limitations:["Assessment admissions are not recorded."]}}}},customerTrace:{snapshotRef:"sha256:customer",result:{status:"NOT_RECORDED",eligibility:"NOT_RECORDED",customerReference:"DEMO-0001",publicationId:"offer",offerVersion:1,variant:"PERSONALIZED",limitations:["No exact variant qualification was recorded."]}}};
it("retries the same approved investigation with an unchanged body and displays the chosen defaults",async()=>{
 request.mockImplementation(async(path:string,method="GET")=>{
   if(method==="POST")throw Error("Temporary dependency failure");
   if(path==="ai/defaults")return {maxEvaluations:10,exploration:4,refinement:3,validation:2,minimumEligible:5,eligibilityFloor:0,acceptanceFloor:0};
   return [];
 });
 render(<Investigations publicationId="offer"/>);
 fireEvent.change(screen.getByLabelText("Your question"),{target:{value:"What was acceptance, and was DEMO-0001 eligible?"}});
 fireEvent.change(screen.getByLabelText("Customer ID or reference (optional)"),{target:{value:"DEMO-0001"}});
 fireEvent.change(screen.getByLabelText("Start date (inclusive)"),{target:{value:"2026-09-01"}});
 fireEvent.change(screen.getByLabelText("End date (inclusive)"),{target:{value:"2026-09-02"}});
 const submit=screen.getByRole("button",{name:"Start investigation"});expect((submit as HTMLButtonElement).disabled).toBe(true);
 fireEvent.click(screen.getByLabelText("I authorize agent execution within this budget."));
 await waitFor(()=>expect((submit as HTMLButtonElement).disabled).toBe(false));fireEvent.click(submit);
 await screen.findByText("Temporary dependency failure");fireEvent.click(submit);
 await waitFor(()=>expect(request.mock.calls.filter(c=>c[1]==="POST")).toHaveLength(2));
 const attempts=request.mock.calls.filter(c=>c[1]==="POST");expect(attempts[0][0]).toBe("ai/feedback/investigations");expect(attempts[1][2]).toEqual(attempts[0][2]);expect(attempts[0][2].scope).toEqual({timezone:"America/Los_Angeles",dateBasis:"FIRST_ASSESSED",startDate:"2026-09-01",endDate:"2026-09-02"});expect(attempts[0][2].customerReference).toBe("DEMO-0001");
});
it("shows server figures, missing historical evidence and immutable evidence links without inventing a gap",()=>{
 const evidence=vi.fn();render(<InvestigationAnswer answer={answer} onEvidence={evidence}/>);
 expect(screen.getByText("40.0%")).toBeTruthy();expect(screen.getByText("80.0%")).toBeTruthy();expect(screen.getByText(/This does not mean the customer was ineligible/)).toBeTruthy();expect(screen.queryByText(/Validated forecast gap:/)).toBeNull();
 fireEvent.click(screen.getByRole("button",{name:"Open customer decision evidence"}));expect(evidence).toHaveBeenCalledWith("sha256:customer");expect(screen.getAllByText(/America\/Los_Angeles/)[0]).toBeTruthy();
});

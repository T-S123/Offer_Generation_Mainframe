// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Presenter } from "./Presenter";

const request = vi.hoisted(() => vi.fn());
vi.mock("./api", async () => ({...(await vi.importActual("./api")), api: request}));
beforeEach(() => {
  request.mockReset();
  localStorage.clear();
  HTMLDialogElement.prototype.showModal = function() { this.open = true; };
  HTMLDialogElement.prototype.close = function() { this.open = false; };
});
afterEach(cleanup);

it("shows exact prepared identities after reload and retains the summary in the presenter menu", async () => {
  const job={state:"READY",action:"A2A",requestId:"prepared-show",prepared:{
    customerName:"A2A Recording Customer",customerReference:"A2A-FILM-001",customerId:"customer-created-id",
    profileVersion:2,draftName:"A2A filming personal loan",draftId:"draft-created-id",draftVersion:2,
    catalogOfferId:"DEMO-PL-1",catalogOfferName:"Personal loan demo 1",discoverySeed:20261105,validationSeed:20261104
  }};
  request.mockImplementation(async path=>path==="session"?{}:job);
  const home=vi.fn();
  const component=render(<Presenter onComplete={home}/>);
  await screen.findByRole("heading",{name:"A2A demo ready"});
  expect(screen.getByText("customer-created-id")).toBeTruthy();
  expect(screen.getByText("draft-created-id")).toBeTruthy();
  expect(screen.getByText("DEMO-PL-1")).toBeTruthy();
  expect(screen.getByText(/Preparation has not published an AI offer/)).toBeTruthy();
  expect(home).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button",{name:"Done"}));
  expect(localStorage.getItem("lending.presenter.summaryAcknowledged")).toBe("prepared-show");
  component.unmount();
  render(<Presenter onComplete={home}/>);
  await act(async()=>{});
  expect(screen.queryByRole("dialog")).toBeNull();
  await open();
  expect(screen.getByText("customer-created-id")).toBeTruthy();
});


async function open() {
  fireEvent.click(screen.getByRole("button", {name: "Presenter controls"}));
  await waitFor(() => expect((screen.getByRole("button", {name: "Demo A2A"}) as HTMLButtonElement).disabled).toBe(false));
}

it("reviews a clear, posts one confirmed command and returns home on its completion", async () => {
  let status: {state: string; requestId?: string; message?: string} = {state: "IDLE"};
  request.mockImplementation(async (path, method, body) => {
    if (path === "session") return {};
    if (path === "presenter/status") return status;
    expect(method).toBe("POST");
    status = {state: "RUNNING", requestId: body.requestId, message: "Resetting the catalog…"};
    return status;
  });
  const home = vi.fn();
  render(<Presenter onComplete={home}/>);
  await open();
  fireEvent.click(screen.getByRole("button", {name: "Clear all data"}));
  expect(request.mock.calls.filter(([p]) => p === "presenter/reset")).toHaveLength(0);
  fireEvent.click(screen.getByRole("button", {name: "Clear demo data"}));
  await screen.findByText("Resetting the catalog…");
  expect(screen.queryByRole("button", {name: "Demo A2A"})).toBeNull();
  const posts = request.mock.calls.filter(([p]) => p === "presenter/reset");
  expect(posts).toHaveLength(1);
  expect(posts[0][2]).toEqual({action: "CLEAR", confirmed: true, requestId: expect.any(String)});
  expect(home).not.toHaveBeenCalled();
  status = {...status, state: "READY"};
  fireEvent(window, new Event("focus"));
  await waitFor(() => expect(home).toHaveBeenCalledTimes(1));
  fireEvent(window, new Event("focus"));
  expect(home).toHaveBeenCalledTimes(1);
});

it("reuses the request ID if the prepare acknowledgement was lost", async () => {
  request.mockImplementation(async path => {
    if (path === "presenter/reset") throw new Error("Acknowledgement unavailable");
    return path === "session" ? {} : {state:"IDLE"};
  });
  render(<Presenter onComplete={vi.fn()}/>);
  await open();
  fireEvent.click(screen.getByRole("button", {name:"Demo A2A"}));
  fireEvent.click(screen.getByRole("button", {name:"Prepare A2A demo"}));
  await screen.findByText("Acknowledgement unavailable");
  fireEvent.click(screen.getByRole("button", {name:"Prepare A2A demo"}));
  await waitFor(() => expect(request.mock.calls.filter(([p]) => p === "presenter/reset")).toHaveLength(2));
  const posts = request.mock.calls.filter(([p]) => p === "presenter/reset");
  expect(posts[0][2].action).toBe("A2A");
  expect(posts[0][2]).toEqual(posts[1][2]);
});

it("ignores a previous completed job on mount and reacts to another tab completing a new job", async () => {
  let id = "last-show";
  request.mockImplementation(async path => path === "session" ? {} : {state:"READY", requestId:id});
  const home = vi.fn();
  render(<Presenter onComplete={home}/>);
  await open();
  expect(home).not.toHaveBeenCalled();
  await act(async () => { fireEvent.click(screen.getByRole("button", {name:"Close dialog"})); });
  id = "next-show";
  fireEvent(window, new Event("focus"));
  await waitFor(() => expect(home).toHaveBeenCalledTimes(1));
  expect(request.mock.calls.some(([p]) => p === "presenter/reset")).toBe(false);
});

it("keeps a saved position reachable and lets keyboard users move and reopen the control", async () => {
  request.mockResolvedValue({state:"IDLE"});
  localStorage.setItem("lending.presenter.position", '{"x":-100,"y":99999}');
  const component = render(<Presenter onComplete={vi.fn()}/>);
  const control = screen.getByRole("button", {name:"Presenter controls"});
  expect(control.style.left).toBe("6px");
  expect(parseInt(control.style.top) + 52).toBeLessThan(window.innerHeight);
  fireEvent.keyDown(control, {key:"ArrowRight"});
  expect(control.style.left).toBe("30px");
  component.unmount();
  render(<Presenter onComplete={vi.fn()}/>);
  expect(screen.getByRole("button", {name:"Presenter controls"}).style.left).toBe("30px");
  await open();
  expect(screen.getByRole("dialog")).toBeTruthy();
});

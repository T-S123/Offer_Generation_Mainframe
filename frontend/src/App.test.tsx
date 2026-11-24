// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { App } from "./App";
import type { Session } from "./api";

const request = vi.hoisted(() => vi.fn());
vi.mock("./api", async () => ({ ...await vi.importActual("./api"), api: request }));
vi.mock("./Business", () => ({ BusinessWorkspace: () => <p>Performance briefing</p> }));
vi.mock("./Presenter", () => ({ Presenter: ({ bankName }: { bankName: string }) => <span>Presenter for {bankName}</span> }));
afterEach(cleanup);

it("selects a bank before the workspace and switches its brand, theme and Presenter together", async () => {
  let session: Session = { bankId: "", customerId: "", mode: "ANONYMOUS", csrfToken: "demo" };
  request.mockImplementation(async (path: string, method = "GET", body?: { bankId: Session["bankId"] }) => {
    if (path === "session/bank" && method === "POST") session = { ...session, bankId: body!.bankId };
    if (path === "session/business") session = { ...session, mode: "BUSINESS" };
    if (path === "session/logout") session = { ...session, mode: "ANONYMOUS" };
    return session;
  });
  render(<App />);
  expect(await screen.findByRole("heading", { name: "Choose a bank" })).toBeTruthy();
  expect(screen.queryByText("Business workspace")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Open Anna Karenina Bank" }));
  expect(await screen.findByRole("heading", { name: "Anna Karenina Bank", level: 1 })).toBeTruthy();
  expect(document.documentElement.dataset.bank).toBe("anna");
  expect(document.title).toBe("Anna Karenina Bank");
  expect(screen.getByText("Presenter for Anna Karenina Bank")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Enter Business workspace" }));
  expect(await screen.findByText("Performance briefing", { selector: "p" })).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
  fireEvent.click(await screen.findByRole("button", { name: "Change bank" }));
  fireEvent.click(await screen.findByRole("button", { name: "Open Emma Woodhouse Bank" }));
  expect(await screen.findByRole("heading", { name: "Emma Woodhouse Bank", level: 1 })).toBeTruthy();
  expect(document.documentElement.dataset.bank).toBe("emma");
  expect(screen.getByText("Presenter for Emma Woodhouse Bank")).toBeTruthy();
  expect(screen.queryByText("Presenter for Anna Karenina Bank")).toBeNull();
});

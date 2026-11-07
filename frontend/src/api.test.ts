import { afterEach, describe, it, expect, vi } from "vitest";
import { api, ApiError } from "./api";
afterEach(() => vi.unstubAllGlobals());
describe("browser transport", () => {
  it("coalesces simultaneous reads but never repeats a mutation", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response('{"id":"one"}', { status: 200 }));
    vi.stubGlobal("fetch", fetch);
    const [a, b] = await Promise.all([api("same"), api("same")]);
    expect(a).toEqual(b);
    expect(fetch).toHaveBeenCalledTimes(1);
    fetch.mockResolvedValueOnce(
      new Response('{"error":"Stale version"}', { status: 409 }),
    );
    await expect(
      api("customer", "PUT", { expectedVersion: 1 }),
    ).rejects.toMatchObject({ status: 409 });
    expect(fetch).toHaveBeenCalledTimes(2);
  });
  it("uses the session CSRF token and preserves the request key and version", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(new Response('{"csrfToken":"test-csrf"}'))
      .mockResolvedValueOnce(new Response("{}"));
    vi.stubGlobal("fetch", fetch);
    await api("session");
    await api("business/drafts", "POST", { expectedVersion: 4 }, "stable-key");
    const request = fetch.mock.calls[1][1];
    expect(request.headers["X-CSRF-Token"]).toBe("test-csrf");
    expect(request.headers["Idempotency-Key"]).toBe("stable-key");
    expect(request.body).toBe('{"expectedVersion":4}');
    expect(request.headers.Authorization).toBeUndefined();
  });
  it("limits queued upstream requests to two without leaking capacity after errors", async () => {
    let active = 0,
      peak = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        active++;
        peak = Math.max(peak, active);
        await new Promise((r) => setTimeout(r, 3));
        active--;
        return new Response("{}");
      }),
    );
    await Promise.all(Array.from({ length: 12 }, (_, i) => api("queue/" + i)));
    expect(peak).toBe(2);
  });
  it("reports non-JSON service failures", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(new Response("<html>oops</html>", { status: 503 })),
    );
    await expect(api("failure")).rejects.toBeInstanceOf(ApiError);
  });
});

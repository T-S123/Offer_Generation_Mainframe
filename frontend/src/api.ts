/** Browser transport for the local web adapter. Credentials never enter this module. */
export type Data = Record<string, any>;
export type Session = {
  mode: "ANONYMOUS" | "CUSTOMER" | "BUSINESS";
  customerId: string;
  csrfToken: string;
};
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
let csrf = "";
let active = 0;
const waiting: Array<() => void> = [];
async function slot() {
  if (active < 2) {
    active++;
    return;
  }
  await new Promise<void>((resolve) => waiting.push(resolve));
}
function release() {
  const next = waiting.shift();
  if (next) next();
  else active--;
}
const inFlight = new Map<string, Promise<any>>();
export function api<T = Data>(
  path: string,
  method = "GET",
  body?: unknown,
  key?: string,
): Promise<T> {
  if (method !== "GET") return request<T>(path, method, body, key);
  if (inFlight.has(path)) return inFlight.get(path)!;
  const pending = request<T>(path, method, body, key).finally(() =>
    inFlight.delete(path),
  );
  inFlight.set(path, pending);
  return pending;
}
async function request<T>(
  path: string,
  method: string,
  body?: unknown,
  key?: string,
): Promise<T> {
  await slot();
  try {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (method !== "GET") {
      headers["Content-Type"] = "application/json";
      headers["X-CSRF-Token"] = csrf;
    }
    if (key) headers["Idempotency-Key"] = key;
    const response = await fetch("/web-api/" + path, {
      method,
      headers,
      credentials: "same-origin",
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    let value: any;
    try {
      value = JSON.parse(text);
    } catch {
      throw new ApiError(
        "The service returned an unreadable response. Check the local engine.",
        response.status,
      );
    }
    if (!response.ok)
      throw new ApiError(
        value.error || "The request could not be completed.",
        response.status,
      );
    if (value.csrfToken) csrf = value.csrfToken;
    return value as T;
  } finally {
    release();
  }
}
export function errorText(error: unknown) {
  return error instanceof Error
    ? error.message
    : "The request could not be completed.";
}
export const key = () => crypto.randomUUID();
export const id = (value: string) => encodeURIComponent(value);

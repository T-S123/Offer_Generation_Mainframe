import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { api, errorText } from "./api";
import { human, number } from "./model";
export function useRemote<T>(path: string | null, poll = 0) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  const refresh = useCallback(() => setRevision((n) => n + 1), []);
  const oldPath = useRef(path);
  useEffect(() => {
    let alive = true,
      timer: ReturnType<typeof setTimeout>;
    if (oldPath.current !== path) {
      setData(undefined);
      oldPath.current = path;
    }
    if (!path) return;
    async function load() {
      setLoading(true);
      try {
        const value = await api<T>(path!);
        if (alive) {
          setData(value);
          setError("");
        }
      } catch (e) {
        if (alive) setError(errorText(e));
      } finally {
        if (alive) {
          setLoading(false);
          if (poll)
            timer = setTimeout(
              load,
              document.hidden ? Math.max(poll, 15000) : poll,
            );
        }
      }
    }
    void load();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [path, poll, revision]);
  return { data, error, loading, refresh };
}
export function Icon({ name = "arrow" }: { name?: string }) {
  const paths: Record<string, string> = {
    arrow: "M5 12h14m-6-6 6 6-6 6",
    grid: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
    user: "M20 21v-2a7 7 0 0 0-14 0v2 M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
    chart: "M4 20V4m0 16h17 M8 15l4-5 4 3 5-8",
    flask: "M9 3h6 M10 3v6L4 19q-1 2 2 2h12q3 0 2-2L14 9V3 M7 15h10",
    spark: "m12 3 3 6 6 3-6 3-3 6-3-6-6-3 6-3z",
    book: "M4 3h13a3 3 0 0 1 3 3v15H6a3 3 0 0 1-3-3V6q0-3 3-3 M4 17h16",
    logout: "M9 4H4v16h5 M12 12h9m-4-4 4 4-4 4",
    refresh: "M20 7v5h-5 M4 17v-5h5 M6 6a8 8 0 0 1 14 6 M4 12a8 8 0 0 0 14 6",
  };
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={paths[name] || paths.arrow} />
    </svg>
  );
}
export function Brand() {
  return (
    <div className="brand">
      <span className="brand-mark">
        <i />
        <i />
        <i />
      </span>
      <span>
        Lending<span className="brand-light">Studio</span>
      </span>
    </div>
  );
}
export function Notice({
  children,
  tone = "error",
}: {
  children: ReactNode;
  tone?: string;
}) {
  return children ? (
    <div
      className={"notice " + tone}
      role={tone === "error" ? "alert" : "status"}
    >
      {children}
    </div>
  ) : null;
}
export function Empty({
  title,
  children,
}: {
  title: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty">
      <span className="empty-icon">
        <Icon name="grid" />
      </span>
      <h3>{title}</h3>
      <p>{children}</p>
    </div>
  );
}
export function PageTitle({
  eyebrow,
  title,
  children,
  action,
}: {
  eyebrow: string;
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <header className="page-title">
      <div>
        <span className="eyebrow">{eyebrow}</span>
        <h1>{title}</h1>
        {children && <p>{children}</p>}
      </div>
      {action}
    </header>
  );
}
export function Badge({
  children,
  tone = "",
}: {
  children: ReactNode;
  tone?: string;
}) {
  return <span className={"badge " + tone}>{children}</span>;
}
export function Stat({
  label,
  value,
  detail,
}: {
  label: string;
  value: ReactNode;
  detail?: ReactNode;
}) {
  return (
    <div className="stat">
      <span>{label}</span>
      <strong>{value}</strong>
      {detail && <small>{detail}</small>}
    </div>
  );
}
export function Evidence({
  value,
  label = "Inspect supporting details",
}: {
  value: unknown;
  label?: string;
}) {
  return (
    <details className="evidence">
      <summary>{label}</summary>
      <pre>{JSON.stringify(value, null, 2)}</pre>
    </details>
  );
}
export function Modal({
  title,
  children,
  onClose,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    d?.showModal();
    return () => d?.close();
  }, []);
  return (
    <dialog ref={ref} className="modal" onCancel={onClose}>
      <div className="modal-head">
        <h2>{title}</h2>
        <button
          className="icon-button"
          onClick={onClose}
          aria-label="Close dialog"
        >
          ×
        </button>
      </div>
      {children}
    </dialog>
  );
}
export function Progress({
  value,
  max,
  label,
}: {
  value: number;
  max: number;
  label: string;
}) {
  return (
    <div className="progress">
      <div>
        <span>{label}</span>
        <b>
          {number(value)} / {number(max)}
        </b>
      </div>
      <progress value={value || 0} max={max || 1} />
    </div>
  );
}
export function Field({
  name,
  label,
  value,
  onChange,
  type = "text",
  required = false,
  min,
  max,
  step,
  readOnly = false,
}: {
  name: string;
  label?: string;
  value: any;
  onChange: (value: string) => void;
  type?: string;
  required?: boolean;
  min?: number;
  max?: number;
  step?: number | string;
  readOnly?: boolean;
}) {
  return (
    <label className="field">
      <span>
        {label || human(name)}
        {required && <em> *</em>}
      </span>
      <input
        name={name}
        type={type}
        value={value ?? ""}
        onChange={(e) => onChange(e.target.value)}
        required={required}
        min={min}
        max={max}
        step={step}
        readOnly={readOnly}
      />
    </label>
  );
}
export function Loading() {
  return (
    <div className="loading" role="status">
      <span className="spinner" />
      Loading your workspace…
    </div>
  );
}

import { useEffect, useState, type FormEvent } from "react";
import { api, errorText, type Data, type Session } from "./api";
import { Brand, Empty, Field, Icon, Loading, Notice, useRemote } from "./ui";
import { CustomerForm, CustomerWorkspace } from "./Customer";
import { BusinessWorkspace } from "./Business";

/** Plain Customer and Business screens use owner APIs, with separate simulation and investigation navigation and functional guidance. */
export function App() {
  const [session, setSession] = useState<Session>(),
    [error, setError] = useState(""),
    [entry, setEntry] = useState("home"),
    [view, setView] = useState("overview"),
    [busy, setBusy] = useState(false);
  async function sync() {
    try {
      const s = await api<Session>("session");
      setSession(s);
      setError("");
      if (s.mode === "BUSINESS") setView("performance");
    } catch (e) {
      setError(errorText(e));
    }
  }
  useEffect(() => {
    void sync();
  }, []);
  async function business() {
    setBusy(true);
    try {
      setSession(await api<Session>("session/business", "POST", {}));
      setView("performance");
      setError("");
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  async function logout() {
    try {
      setSession(await api<Session>("session/logout", "POST", {}));
      setEntry("home");
      setView("overview");
      setError("");
    } catch (e) {
      setError(errorText(e));
    }
  }
  if (!session)
    return (
      <div className="boot">
        <Brand />
        <Notice>{error}</Notice>
        {error ? (
          <button className="button primary" onClick={sync}>
            Reconnect
          </button>
        ) : (
          <Loading />
        )}
      </div>
    );
  if (session.mode === "ANONYMOUS")
    return (
      <div className="entry-shell">
        <header className="entry-header">
          <button className="brand-button" onClick={() => setEntry("home")}>
            <Brand />
          </button>
          <span className="local-label">
            <i />
            LOCAL DEMO
          </span>
        </header>
        <Notice>{error}</Notice>
        {entry === "signup" ? (
          <main className="onboarding">
            <CustomerForm
              onBack={() => setEntry("home")}
              onSaved={() => {
                setView("overview");
                void sync();
              }}
            />
          </main>
        ) : entry === "login" ? (
          <Login
            onBack={() => setEntry("home")}
            onSession={(s) => {
              setSession(s);
              setView("overview");
            }}
          />
        ) : (
          <main className="welcome">
            <section className="welcome-copy">
              <h1>Lending Studio</h1>
            </section>
            <section className="entry-options">
              <div className="entry-card">
                <span className="entry-symbol">
                  <Icon name="user" />
                </span>
                <h2>Customer portal</h2>
                <button
                  className="button primary wide"
                  onClick={() => setEntry("signup")}
                >
                  Create a customer profile <Icon />
                </button>
                <button
                  className="text-button centered"
                  onClick={() => setEntry("login")}
                >
                  Already a customer? Log in <Icon />
                </button>
              </div>
              <div className="entry-card business-entry">
                <span className="entry-symbol">
                  <Icon name="chart" />
                </span>
                <div>
                  <h2>Business workspace</h2>
                </div>
                <button
                  className="button light wide"
                  disabled={busy}
                  onClick={business}
                >
                  Enter Business workspace <Icon />
                </button>
              </div>
              <small className="entry-note">
                Local demo access uses customer selection and Business mode.
                Password accounts are not enabled.
              </small>
            </section>
          </main>
        )}
        <footer className="entry-footer">
          <span>Lending Intelligence Mainframe</span>
        </footer>
      </div>
    );
  const businessMode = session.mode === "BUSINESS";
  const nav = businessMode
    ? [
        ["performance", "chart", "Offer performance"],
        ["experiments", "flask", "Simulations"],
        ["ai", "spark", "A2A exploration"],
        ["investigations", "book", "Investigations"],
        ["policies", "book", "Policy evidence"],
      ]
    : [
        ["overview", "grid", "Overview"],
        ["profile", "user", "My information"],
        ["offers", "chart", "My offers"],
      ];
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <Brand />
        <div className="workspace-label">
          {businessMode ? "BUSINESS WORKSPACE" : "CUSTOMER PORTAL"}
        </div>
        <nav aria-label="Workspace navigation">
          {nav.map(([v, icon, title]) => (
            <button
              key={v}
              className={view === v ? "active" : ""}
              onClick={() => setView(v)}
            >
              <Icon name={icon} />
              {title}
              {view === v && <span className="nav-dot" />}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="session-card">
            <span className="avatar">{businessMode ? "B" : "C"}</span>
            <div>
              <b>{businessMode ? "Business analyst" : "Customer session"}</b>
              <small>Local demonstration</small>
            </div>
          </div>
          <button onClick={logout}>
            <Icon name="logout" />
            Sign out
          </button>
        </div>
      </aside>
      <div className="workspace">
        <header className="workspace-header">
          <span>
            {businessMode ? "Business" : "Customer"}{" "}
            <span className="crumb">/</span>{" "}
            {nav.find((n) => n[0] === view)?.[2]}
          </span>
          <span className="local-label">
            <i />
            LOCAL WORKSPACE
          </span>
        </header>
        <main className="main-content">
          <Notice>{error}</Notice>
          {businessMode ? (
            <BusinessWorkspace view={view} setView={setView} />
          ) : (
            <CustomerWorkspace view={view} setView={setView} />
          )}
        </main>
      </div>
    </div>
  );
}
function Login({
  onBack,
  onSession,
}: {
  onBack: () => void;
  onSession: (s: Session) => void;
}) {
  const [offset, setOffset] = useState(0),
    [customerId, setCustomerId] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const customers = useRemote<Data>("customers?offset=" + offset + "&limit=12");
  async function login(value: string) {
    if (!value.trim()) return;
    setBusy(true);
    setError("");
    try {
      onSession(
        await api<Session>("session/customer", "POST", {
          customerId: value.trim(),
        }),
      );
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="login">
      <button className="text-button" onClick={onBack}>
        ← Back
      </button>
      <h1>Customer login</h1>
      <p>Enter your customer ID or select a saved demo profile.</p>
      <form
        className="login-form"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          void login(customerId);
        }}
      >
        <Field
          name="customerId"
          label="Customer ID"
          value={customerId}
          onChange={setCustomerId}
          required
        />
        <button className="button primary" disabled={busy}>
          Open my profile <Icon />
        </button>
      </form>
      <Notice>{error || customers.error}</Notice>
      <h2>Saved customers</h2>
      {customers.data?.items?.length ? (
        <div className="customer-list">
          {customers.data.items.map((c: Data) => (
            <button key={c.id} disabled={busy} onClick={() => login(c.id)}>
              <span className="avatar">{c.displayName?.slice(0, 1)}</span>
              <span>
                <b>{c.displayName}</b>
                <small>{c.id}</small>
              </span>
              <Icon />
            </button>
          ))}
        </div>
      ) : (
        <Empty
          title={
            customers.loading ? "Loading customers…" : "No saved customers yet"
          }
        >
          Create a customer profile to start your application.
        </Empty>
      )}
      <div className="form-actions">
        <button
          className="button secondary"
          disabled={offset === 0}
          onClick={() => setOffset(Math.max(0, offset - 12))}
        >
          Previous
        </button>
        <span>{customers.data?.total ?? 0} saved profiles</span>
        <button
          className="button secondary"
          disabled={offset + 12 >= (customers.data?.total ?? 0)}
          onClick={() => setOffset(offset + 12)}
        >
          Next
        </button>
      </div>
    </main>
  );
}

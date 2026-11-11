import { Personalization, PersonalizedOffers } from "./Personalization";
import { useState, type FormEvent } from "react";
import { api, errorText, id, key, type Data } from "./api";
import {
  customerPayload,
  latestProfile,
  labels,
  human,
  money,
  pct,
  date,
} from "./model";
import {
  Badge,
  Empty,
  Evidence,
  Field,
  Icon,
  Loading,
  Modal,
  Notice,
  PageTitle,
  Stat,
  useRemote,
} from "./ui";

const groups = [
  ["About you", ["displayName", "externalReference"]],
  [
    "Financial profile",
    [
      "monthlyIncomeUsd",
      "monthlyDebtPaymentsUsd",
      "creditScore",
      "delinquencies12m",
      "creditUtilizationPct",
    ],
  ],
  [
    "What you are looking for",
    [
      "personalLoanAmountUsd",
      "requestedCardLimitUsd",
      "autoLoanAmountUsd",
      "vehicleValueUsd",
      "vehicleAgeYears",
    ],
  ],
  [
    "Your banking relationship",
    ["bankingTenureMonths", "depositBalanceUsd", "monthlySpendUsd"],
  ],
] as const;
const products = [
  "PERSONAL_LOAN",
  "CREDIT_CARD",
  "AUTO_LOAN",
  "CHECKING",
  "SAVINGS",
  "MORTGAGE",
];
/** Captures customer-supplied facts with plain labels and field guidance; blanks remain unknown and owner APIs evaluate eligibility. */
export function CustomerForm({
  customer,
  onSaved,
  onBack,
}: {
  customer?: Data;
  onSaved: () => void;
  onBack: () => void;
}) {
  const profile = customer ? latestProfile(customer) : undefined;
  const [form, setForm] = useState<Data>(
    profile
      ? structuredClone(profile.data)
      : {
          displayName: "",
          externalReference: "",
          marketingOptIn: "",
          prescreenOptOut: "",
          existingProducts: [],
          preferredChannels: [],
        },
  );
  const [review, setReview] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const change = (name: string, value: unknown) =>
    setForm((f) => ({ ...f, [name]: value }));
  const toggle = (name: string, value: string) =>
    change(
      name,
      (form[name] ?? []).includes(value)
        ? form[name].filter((v: string) => v !== value)
        : [...(form[name] ?? []), value],
    );
  async function save() {
    setBusy(true);
    setError("");
    try {
      const value = customerPayload(form);
      await api(
        customer ? "customer" : "customers",
        customer ? "PUT" : "POST",
        customer
          ? { expectedVersion: profile.version, customer: value }
          : value,
      );
      onSaved();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  function submit(e: FormEvent) {
    e.preventDefault();
    setError("");
    setReview(true);
  }
  return (
    <>
      <PageTitle
        eyebrow={customer ? "Your profile" : "Customer signup"}
        title={customer ? "Edit profile" : "Create customer profile"}
      >
        You can leave unknown financial values blank.
      </PageTitle>
      <form onSubmit={submit} className="form-stack">
        <Notice>{error}</Notice>
        {groups.map(([title, fields], index) => (
          <section className="panel" key={title}>
            <div className="section-heading">
              <span className="step-index">0{index + 1}</span>
              <h2>{title}</h2>
            </div>
            <div className="form-grid">
              {fields.map((name) => (
                <Field
                  key={name}
                  name={name}
                  label={labels[name]}
                  value={form[name]}
                  onChange={(v) => change(name, v)}
                  type={index === 0 ? "text" : "number"}
                  required={name === "displayName"}
                  min={name === "creditScore" ? 300 : 0}
                  max={
                    name === "creditScore"
                      ? 850
                      : name === "creditUtilizationPct"
                        ? 100
                        : undefined
                  }
                  step={
                    name.endsWith("Usd") || name.endsWith("Pct") ? "0.01" : "1"
                  }
                />
              ))}
            </div>
          </section>
        ))}
        <section className="panel">
          <div className="section-heading">
            <span className="step-index">05</span>
            <h2>Your preferences</h2>
          </div>
          <p className="muted">
            Choose your consent preferences explicitly. These are used by the
            qualification engine.
          </p>
          <div className="form-grid">
            {["marketingOptIn", "prescreenOptOut"].map((name) => (
              <label className="field" key={name}>
                <span>{labels[name]}</span>
                <select
                  value={form[name] == null ? "" : String(form[name])}
                  onChange={(e) => change(name, e.target.value)}
                >
                  <option value="">Not specified</option>
                  <option value="true">Yes</option>
                  <option value="false">No</option>
                </select>
              </label>
            ))}
          </div>
          <fieldset>
            <legend>Products you already hold</legend>
            <div className="check-grid">
              {products.map((p) => (
                <label className="check" key={p}>
                  <input
                    type="checkbox"
                    checked={(form.existingProducts ?? []).includes(p)}
                    onChange={() => toggle("existingProducts", p)}
                  />
                  {human(p)}
                </label>
              ))}
            </div>
          </fieldset>
          <fieldset>
            <legend>Preferred contact channels</legend>
            <div className="check-grid">
              {["EMAIL", "SMS", "POSTAL"].map((p) => (
                <label className="check" key={p}>
                  <input
                    type="checkbox"
                    checked={(form.preferredChannels ?? []).includes(p)}
                    onChange={() => toggle("preferredChannels", p)}
                  />
                  {human(p)}
                </label>
              ))}
            </div>
            <small>
              Demo contacts are generated locally. No email, text, or postal
              message is sent.
            </small>
          </fieldset>
        </section>
        <div className="form-actions">
          <button type="button" className="button secondary" onClick={onBack}>
            Back
          </button>
          <button className="button primary" type="submit">
            Review information <Icon />
          </button>
        </div>
      </form>
      {review && (
        <Modal
          title={
            customer ? "Review profile changes" : "Review your application"
          }
          onClose={() => !busy && setReview(false)}
        >
          <p>
            Saving evaluates your information and starts qualification.{" "}
            {customer && "Your existing profile history is retained."}
          </p>
          <dl className="review-grid">
            {Object.entries(customerPayload(form)).map(([k, v]) => (
              <div key={k}>
                <dt>{labels[k] || human(k)}</dt>
                <dd>
                  {v == null
                    ? "Not specified"
                    : Array.isArray(v)
                      ? v.map(human).join(", ") || "None"
                      : typeof v === "boolean"
                        ? v
                          ? "Yes"
                          : "No"
                        : String(v)}
                </dd>
              </div>
            ))}
          </dl>
          <Notice>{error}</Notice>
          <div className="form-actions">
            <button
              className="button secondary"
              disabled={busy}
              onClick={() => setReview(false)}
            >
              Keep editing
            </button>
            <button className="button primary" disabled={busy} onClick={save}>
              {busy
                ? "Saving…"
                : customer
                  ? "Save updated profile"
                  : "Create customer"}
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}

/** Customer dashboard exposes session quotes beside standard offers, starts chat after updated offers become available, and confirms exact owner-reviewed terms. */
export function CustomerWorkspace({
  view,
  setView,
}: {
  view: string;
  setView: (view: string) => void;
}) {
  const customer = useRemote<Data>("customer", 3000);
  const offers = useRemote<Data>(
    view === "offers" ? "customer/offers?limit=50" : null,
    view === "offers" ? 5000 : 0,
  );
  const [quote, setQuote] = useState<Data>(),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [cursor, setCursor] = useState("");
  const page = useRemote<Data>(
    view === "offers" && cursor
      ? "customer/offers?limit=50&after=" + id(cursor)
      : null,
  );
  if (!customer.data)
    return (
      <>
        <Notice>{customer.error}</Notice>
        <Loading />
      </>
    );
  const data = customer.data,
    profile = latestProfile(data),
    input = profile.data;
  const pipeline = data.pipeline;
  const readyForConversation = ["COMPLETED", "NO_ELIGIBLE_OFFERS", "ATTENTION_REQUIRED", "STOPPED"].includes(pipeline?.state)
    || (pipeline?.state === "OFFERS_PENDING" && !!pipeline.runId
      && (data.offerView?.items?.some((offer: Data) => offer.qualification?.runId === pipeline.runId) ?? false));
  if (view === "profile")
    return (
      <CustomerForm
        key={data.id}
        customer={data}
        onBack={() => setView("overview")}
        onSaved={() => {
          customer.refresh();
          setView("overview");
          setNotice(
            "Profile saved. Qualification is processing your updated information.",
          );
        }}
      />
    );
  async function inspect(offer: Data, kind: string) {
    setBusy(true);
    setError("");
    try {
      const fresh = await api("customer/offers/" + id(offer.id));
      const option = kind === "ORIGINAL" ? fresh.preScreen : fresh.marketing;
      if (!option?.terms)
        throw new Error(
          "This choice is no longer available. Refresh your offers.",
        );
      setQuote({
        offerId: fresh.id,
        sourceVersion: fresh.qualification.responseVersion,
        kind,
        terms: option.terms,
        requestId: key(),
      });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  async function inspectPersonalized(offerId: string) {
    setBusy(true); setError("");
    try {
      const quote = await api("customer/session-offers/" + id(offerId));
      const fresh=quote.offer;
      if(!fresh.personalized?.terms) throw new Error("The personalized offer is no longer available. Reload your offers.");
      setQuote({offerId:quote.id,sessionOffer:true,sourceVersion:fresh.sourceVersion,kind:"PERSONALIZED",terms:fresh.personalized.terms,requestId:key()});
    } catch(e) {setError(errorText(e));} finally {setBusy(false);}
  }
  async function confirm() {
    if (!quote) return;
    setBusy(true);
    setError("");
    try {
      await api(
        (quote.sessionOffer?"customer/session-offers/":"customer/offers/") + id(quote.offerId) + "/selections",
        "POST",
        {
          requestId: quote.requestId,
          sourceVersion: quote.sourceVersion,
          kind: quote.kind,
          terms: quote.terms,
        },
      );
      setQuote(undefined);
      setNotice("Your reviewed offer choice has been saved.");
      offers.refresh();
      page.refresh();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <PageTitle
        eyebrow="Your customer workspace"
        title={
          view === "offers"
            ? "My offers"
            : input.displayName
        }
        action={
          <button
            className="button secondary"
            onClick={() => {
              customer.refresh();
              offers.refresh();
              page.refresh();
            }}
          >
            <Icon name="refresh" />
            Refresh
          </button>
        }
      />
      <Notice tone="success">{notice}</Notice>
      <Notice>
        {error ||
          customer.error ||
          (view === "offers" ? page.error || offers.error : "")}
      </Notice>
      <Personalization key={String(profile.version)} profileVersion={profile.version} readyForConversation={readyForConversation} onReview={()=>setView("offers")} />
      {view === "overview" ? (
        <>
          <div className="stats-grid">
            <Stat
              label="Current profile"
              value={"v" + profile.version}
              detail={date(profile.createdAt)}
            />
            <Stat
              label="Application status"
              value={
                <span className="compact-stat">
                  {human(data.pipeline?.state || "QUEUED")}
                </span>
              }
              detail={data.pipeline?.detail}
            />
            <Stat
              label="Saved products"
              value={input.existingProducts?.length ?? 0}
              detail="Your existing banking relationship"
            />
          </div>
          <div className="two-columns">
            <section className="panel">
              <div className="panel-top">
                <h2>Your information</h2>
                <button
                  className="text-button"
                  onClick={() => setView("profile")}
                >
                  Edit profile <Icon />
                </button>
              </div>
              <dl className="review-grid">
                <div>
                  <dt>Full name</dt>
                  <dd>{input.displayName}</dd>
                </div>
                <div>
                  <dt>Monthly income</dt>
                  <dd>{money(input.monthlyIncomeUsd)}</dd>
                </div>
                <div>
                  <dt>Self-reported credit score</dt>
                  <dd>{input.creditScore ?? "Not specified"}</dd>
                </div>
                <div>
                  <dt>Monthly debt payments</dt>
                  <dd>{money(input.monthlyDebtPaymentsUsd)}</dd>
                </div>
              </dl>
              <p className="identifier">
                Customer ID <span>{data.id}</span>
              </p>
            </section>
            <section className="panel">
              <div className="panel-top">
                <h2>Qualification progress</h2>
                <Badge>{human(data.pipeline?.state || "QUEUED")}</Badge>
              </div>
              <div className="pipeline-steps">
                {[
                  "Underwriting",
                  "Marketing",
                  "Bureau",
                  "Offers",
                  "Delivery",
                ].map((s, i) => (
                  <div key={s}>
                    <span>{i + 1}</span>
                    {s}
                  </div>
                ))}
              </div>
              <p className="muted">
                {data.pipeline?.detail ||
                  "Your application is awaiting processing."}
              </p>
              <button
                className="button primary"
                onClick={() => setView("offers")}
              >
                Explore qualified offers <Icon />
              </button>
            </section>
          </div>
          <section className="panel">
            <div className="panel-top">
              <h2>Underwriting assessments</h2>
              <span className="muted">Current profile</span>
            </div>
            <div className="three-columns">
              {Object.entries(data.currentAssessments ?? {}).map(
                ([product, assessment]) => {
                  const a = assessment as Data;
                  return (
                    <div className="mini-card" key={product}>
                      <h3>{human(product)}</h3>
                      <Badge tone={a.status === "ELIGIBLE" ? "good" : ""}>
                        {human(a.status)}
                      </Badge>
                      <p>
                        {a.reasons?.map(human).join(" · ") ||
                          "No exclusion reasons recorded."}
                      </p>
                    </div>
                  );
                },
              )}
            </div>
          </section>
        </>
      ) : (
        <>
          <PersonalizedOffers onReview={inspectPersonalized} />
          {(cursor ? page.data : offers.data)?.items?.length ? (
            <div className="offer-grid">
              {((cursor ? page.data : offers.data)?.items ?? []).map(
                (offer: Data) => (
                  <section className="panel offer-card" key={offer.id}>
                    <div className="panel-top">
                      <div>
                        <span className="eyebrow">{human(offer.product)}</span>
                        <h2>{offer.qualification?.catalogOfferId}</h2>
                      </div>
                      <Badge tone="good">
                        {human(offer.status || "Qualified")}
                      </Badge>
                    </div>
                    <table>
                      <thead>
                        <tr>
                          <th>Terms</th>
                          <th>Original</th>
                          <th>Personalized</th>
                        </tr>
                      </thead>
                      <tbody>
                        {[
                          ["Amount", "amountUsd"],
                          ["APR", "aprPct"],
                          ["Annual fee", "annualFeeUsd"],
                          ["Term (months)", "termMonths"],
                        ].map(([label, field]) => (
                          <tr key={field}>
                            <td>{label}</td>
                            {["preScreen", "marketing"].map((type) => (
                              <td key={type}>
                                {offer[type]?.terms
                                  ? field === "aprPct"
                                    ? pct(offer[type].terms[field])
                                    : field === "termMonths"
                                      ? offer[type].terms[field]
                                      : money(offer[type].terms[field])
                                  : "—"}
                              </td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    <div className="offer-actions">
                      {[
                        ["Original", "preScreen", "ORIGINAL"],
                        ["Personalized", "marketing", "PERSONALIZED"],
                      ].map(([label, field, kind]) => (
                        <button
                          key={field}
                          className={
                            "button " +
                            (field === "marketing" ? "primary" : "secondary")
                          }
                          disabled={busy || !offer[field]?.terms}
                          onClick={() => inspect(offer, kind)}
                        >
                          Review {label.toLowerCase()}
                        </button>
                      ))}
                    </div>
                    {offer.selection && (
                      <small>
                        Saved selection: {human(offer.selection.kind)}
                      </small>
                    )}
                  </section>
                ),
              )}
            </div>
          ) : (
            <Empty
              title={
                offers.loading
                  ? "Checking your offers…"
                  : "No current offers to show"
              }
            >
              Your application may still be processing, or the current
              qualification rules may exclude it. You can review and update your
              profile.
            </Empty>
          )}
          <div className="form-actions">
            {cursor && (
              <button
                className="button secondary"
                onClick={() => setCursor("")}
              >
                First page
              </button>
            )}
            {(cursor ? page.data : offers.data)?.nextAfter && (
              <button
                className="button secondary"
                onClick={() =>
                  setCursor((cursor ? page.data : offers.data)!.nextAfter)
                }
              >
                More offers <Icon />
              </button>
            )}
          </div>
        </>
      )}
      {quote && (
        <Modal
          title="Confirm your offer choice"
          onClose={() => !busy && setQuote(undefined)}
        >
          <Badge>{human(quote.kind)}</Badge>
          <dl className="review-grid">
            {Object.entries(quote.terms).map(([k, v]) => (
              <div key={k}>
                <dt>{labels[k] || human(k)}</dt>
                <dd>{String(v)}</dd>
              </div>
            ))}
          </dl>
          <p>
            These exact terms will be checked again before your selection is
            saved.
          </p>
          <Notice>{error}</Notice>
          <div className="form-actions">
            <button
              className="button secondary"
              disabled={busy}
              onClick={() => setQuote(undefined)}
            >
              Back
            </button>
            <button
              className="button primary"
              disabled={busy}
              onClick={confirm}
            >
              {busy ? "Confirming…" : "Confirm selection"}
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}

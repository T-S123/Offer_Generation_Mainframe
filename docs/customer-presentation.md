<!-- Explains console and optional graphical Customer terminal access, qualified offer comparison and exact-term selection confirmation. -->
# Step 8: customer presentation

## Run and navigate

From the engine folder in PowerShell, run `scripts/infrastructure.ps1`, `scripts/build.ps1`, then `scripts/run.ps1`. Keep the engine running and use `scripts/terminal.ps1` in a second window. This opens c3270 directly in PowerShell without WSLg; run `scripts/setup.ps1` once if c3270 is missing. `scripts/terminal.ps1 -Gui` retains optional x3270 access when WSLg works. Existing customer and offer data is retained.

The real TN3270 screen uses 24 rows and 80 columns, a black background, blue numbered options, cyan field prompts, yellow headers/function keys and white titles. CardDemo's menu layout is the reference; `Tran` and `Prog` are local screen identifiers, not IBM CICS programs. The implementation continues to use Java's local TN3270 adapter and HTTP APIs with COBOL business rules behind them.

The opening selector offers **01 Customer** and **02 Business**. There is no authentication boundary between these modes: all local demo profiles can be opened, including synthetic ones. Customer/customer-ID correlation on selection prevents accidental cross-profile writes; it is not a substitute for a production identity and authorization service. Returning to the selector clears the session's active customer and drafts.

## Customer workflow

1. Choose Customer → **01 Enter my information**. Complete the existing two-page form and review it. Credit information stays labeled self-reported. Marketing opt-in and prescreen opt-out remain explicit fields; delivery channels can be blank.
2. Save. The existing automatic Steps 1–7 continue. **Application Progress** checks the APIs every two seconds while waiting and opens the eligible offer list when a customer copy becomes available. It does not wait for successful outbound file creation. F3 returns to the customer menu.
3. Choose an offer number. **Compare Your Offers** displays the original catalog offer and any currently qualified personalized alternative: amount/credit limit, APR, annual fee, term, estimated monthly payment and borrowing cost. Original-only offers are supported. Credit-card estimates use the existing one-year full-limit balance assumption; loan estimates use amortization and annual fees.
4. Enter **O** for original or **P** for personalized. A separate confirmation shows the exact amount, APR, fee and term. F3 cancels this confirmation. Enter records the choice only after the API rechecks current qualification and reviewed terms.
5. **Selection Saved** shows a dated receipt. This records a local demo selection; it neither funds a loan nor asserts that the choice will remain eligible indefinitely. Offer copies and campaign packages update asynchronously, usually within seconds. Return to My Offers to view current availability or change the choice.

The customer menu also provides **02 Open a demo customer profile**, **03 View/update my profile**, **04 View my qualified offers**, **05 Application progress**, and **06 My marketing files / delivery status**. File access revalidates the package and customer correlation; local CSV/EBCDIC locations and file checksums are available without exposing Business histories. Profile F4 edits, F5 shows assessment history, F6 opens offers and F2 opens progress. New edits trigger the existing automatic workflow again. Mass generation is only in the separate [Business Simulation Engine](business-simulation.md).

Only the waiting screen refreshes automatically. Editable fields, comparison screens and confirmation screens are not erased by polling. Enter refreshes comparisons and lists. F7/F8 page through offers, including pages emptied by current eligibility filtering. Sessions close after 15 minutes without terminal input. No external model or service receives customer text.

## Visibility and failure behavior

Presentation reads the Step 6 customer copy and revalidates it against current Step 2 qualification, approved bureau decisions and exact variant approvals. Consent withdrawal, prescreen opt-out, global suppression, campaign cancellation, source changes and expiration prevent display/selection. Historical copies never grant eligibility. An expired personalized variant can disappear while a still-qualified original remains available.

Outbound delivery has a separate gate. A customer with no enabled channel, or an execution cooldown/window restriction, can still view and select an otherwise eligible offer. Step 2 qualification itself must remain valid. Presentation never uses successful export, campaign-package availability or file presence as its eligibility source.

If the authority is unavailable, the screen removes cached financial choices and offers retry/back actions. A confirmation keeps its request ID and reviewed terms for safe retries. If those terms or the response version change, confirmation fails with a conflict; the customer returns to review current choices. The customer-copy selection indicator may briefly lag a successful receipt. Model/fit/acceptance diagnostics remain in the Business operations menus; acceptance metrics there remain explicitly simulated.

## API and implementation

- `GET /api/v1/customers/{customerId}`: saved profile and automatic pipeline progress.
- `GET /api/v1/customers/{customerId}/offers?limit=6&after=...`: current qualified customer-copy page.
- `GET /api/v1/customers/{customerId}/offers/{id}`: revalidated financial comparison.
- `GET /api/v1/customers/{customerId}/campaign-packages`: current packages and processing/restriction states.
- `GET /api/v1/customers/{customerId}/campaign-packages/{packageId}/files?format=csv`: revalidated file content, name, encoding and checksum; `dat` selects IBM037.
- `POST /api/v1/offer-creation/customers/{customerId}/offers/{id}/selections`: customer-scoped confirmation, proxied to the independent marketing service's `/api/v1/customers/{customerId}/offers/{id}/selections`.

```json
{
  "requestId": "unique-confirmation-id",
  "sourceVersion": 1,
  "kind": "ORIGINAL",
  "terms": {
    "amountUsd": 10000.00,
    "aprPct": 12.00,
    "annualFeeUsd": 0.00,
    "termMonths": 36
  }
}
```

Use actual displayed terms and the qualification response version, not the copy's storage revision. `PERSONALIZED` is the other supported kind. Customer mismatch returns 404, changed eligibility/terms or conflicting request-ID reuse returns 409, invalid input returns 422, and a dependency outage returns 503. Current eligibility is checked even for a retry. Successful confirmation and its storage event commit atomically. The original unscoped selection route and persisted request fingerprints remain compatible. See `contracts/marketing-offers.openapi.yaml` for the additive contract.

The engine reserves HTTP worker capacity for marketing-service callbacks so simultaneous terminal proxy requests cannot consume every worker needed for qualification checks. Excess local client requests receive retryable 503 responses.

`TerminalServer` owns connection, mode, field and navigation state; `CustomerTerminal` owns presentation state and HTTP calls. Neither reads databases nor implements underwriting/suppression rules. `OfferEngine` checks the displayed choice against current domain results, and `OfferStore` uses a locked current offer plus idempotent request fingerprint to commit the selection. No database migration or new COBOL policy is needed for this presentation step.

## Verification

`scripts/test-bureau.ps1` runs the full isolated PostgreSQL/Kafka/HTTP/COBOL/s3270 suite. Customer terminal tests cover mode isolation, retained form input, automatic progress-to-offers navigation, original/personalized confirmation, stale terms, authority outages and empty filtered pages. Integration tests verify current customer offers despite blocked outbound channels, immediate suppression withdrawal, exact-term confirmation, idempotent retry and preserved Business operations navigation. Real s3270 text/color captures are generated under `services/target/presentation/`; these are test artifacts, not a second frontend.

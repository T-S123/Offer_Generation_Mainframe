<!-- Defines customer input fields, provenance and their relationship to the referenced BIAN concepts. -->
# Customer fields and BIAN mapping

The implementation uses **BIAN 14.0.0** as a semantic/domain reference. BIAN defines service domains and models; it does not mandate a single universal customer application form. This API is a deliberately small application contract with documented BIAN alignment, not a claim of full BIAN API conformance or certification.

## Mapping

| Local field or object | Meaning / unit | BIAN alignment and provenance |
| --- | --- | --- |
| customerId, externalReference, displayName | Engine ID; optional source reference; display name | Party Reference Data Directory: party reference identity. Engine IDs are generated, never inferred from a name. |
| monthlyIncomeUsd | Gross monthly income, USD | Financial facts supporting Underwriting / Customer Credit Rating; application-specific normalized measure. Self-reported on manual entry. |
| monthlyDebtPaymentsUsd | Monthly existing debt payments, USD | Underwriting financial-obligation input; application-specific normalized measure. |
| creditScore | 300-850 score | Customer Credit Rating concept. Manual values are SELF_REPORTED, not a bureau assertion. |
| delinquencies12m, creditUtilizationPct | Count; percent 0-100 | Credit-assessment supporting inputs. Windows and units are local extensions. |
| personalLoanAmountUsd, requestedCardLimitUsd, autoLoanAmountUsd | Requested amount for each candidate product | Customer Offer / Underwriting application context, not a granted offer or approved limit. |
| vehicleValueUsd, vehicleAgeYears | USD; whole years | Collateral Asset Administration / Underwriting concepts. Values are user supplied or synthetic; no appraisal is implied. |
| bankingTenureMonths, existingProducts | Months; product-code list | Customer relationship and Customer Product and Service Directory concepts. Retained for future cohorting. |
| depositBalanceUsd, monthlySpendUsd | USD | Customer Position / behavior context. Optional local snapshot features, not bank transaction evidence. |
| marketingOptIn, prescreenOptOut | True, false, or unknown | Local contact/pre-screen preferences; exact legal consent semantics are institution-specific. No consent is inferred from blank input. |
| profile version, createdAt, origin, creditInformationSource | Snapshot identity and provenance | Local audit/lineage metadata; distinguishes MANUAL, SYNTHETIC and MANUAL_UPDATE records. |
| underwriting assessment | Product, status, reasons, source identity, policy, dates | Underwriting outcome / Customer Credit Rating reference. Imported outcomes remain distinct from simulation outputs. |
| population and members | Frozen profile and decision references | Local pre-screen population projection for future cohorting. |

Only displayName is required to save a draft customer. Missing financial fields generate product-specific INCOMPLETE assessments. No required financial data is filled with fabricated defaults. Invalid syntax/ranges are rejected by the API. Names and external references are not scoring features. No SSN, exact birth date, bank account number, race, religion or free-form narrative is requested by this local form.

The demo scope is US retail lending in USD. The field dictionary distinguishes one-time requested amounts from monthly flows, percentages from fractional values, and unknown from zero. No actual customer data is fetched from any referenced BIAN site.

## Sources consulted

- BIAN official API repository and release descriptions: https://github.com/bian-official/public/blob/main/README.md
- Party Reference Data Directory: https://raw.githubusercontent.com/bian-official/public/main/release14.0.0/semantic-apis/oas3%20/yamls/PartyReferenceDataDirectory.yaml
- Customer Credit Rating: https://raw.githubusercontent.com/bian-official/public/main/release14.0.0/semantic-apis/oas3%20/yamls/CustomerCreditRating.yaml
- Underwriting: https://raw.githubusercontent.com/bian-official/public/main/release14.0.0/semantic-apis/oas3%20/yamls/Underwriting.yaml
- CFPB describes income, debts, credit reports/scores, requested amount and term among personal installment lending factors: https://www.consumerfinance.gov/ask-cfpb/what-is-a-personal-installment-loan-en-2114/

Business thresholds in this project are explicitly invented for simulation. These sources inform domain vocabulary and relevant inputs, not the demonstration threshold values.

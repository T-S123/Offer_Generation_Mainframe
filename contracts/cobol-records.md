<!-- Documents COBOL request and response layouts, numeric encodings and ordered decision reason flags. -->
# COBOL batch contract, version 1

The files contain newline-delimited ASCII DISPLAY records. Java writes the values only after validating types, ranges and decimal scale. GnuCOBOL interprets monetary `PIC 9(09)V99` fields with implied decimals. The Java adapter and COBOL batch program never rely on locale formatting.

Request (`LIUWREQ.cpy`): **74 bytes**, excluding newline, in this order:

| Field | Width |
| --- | ---: |
| Request ordinal | 8 digits |
| Product PL / CC / AL | 2 |
| Income known Y/N + monthly income cents | 1 + 11 |
| Debt known Y/N + monthly debt cents | 1 + 11 |
| Score known Y/N + score | 1 + 3 |
| Delinquencies known Y/N + count | 1 + 2 |
| Utilization known Y/N + percent times 100 | 1 + 5 |
| Requested amount known Y/N + cents | 1 + 11 |
| Vehicle value known Y/N + cents | 1 + 11 |
| Vehicle age known Y/N + years | 1 + 2 |

Unknown numeric values carry N and zeros. Known zero carries Y and zeros. Java passes a separate request for each product, selecting the corresponding requested amount.

Response (`LIUWRES.cpy`): **33 bytes**, excluding newline: ordinal (8), product (2), decision E/D/I (1), policy version (12), ten reason flags (10).

Reason flags, in order: missing required data, income below minimum, credit score below minimum, DTI exceeded, delinquency limit exceeded, utilization exceeded, requested amount outside policy, vehicle too old, LTV exceeded, unsupported product. Each flag is 0 or 1. Policy SIM-2026-001 returns the complete set of applicable flags after checking completeness.

`LIBAT01` reads `DD_LIREQ` and writes `DD_LIRSP`. A file error returns exit code 12. The Java adapter requires exactly one correctly ordered response per request, supported statuses, a policy version and consistent reason flags. It rejects partial/malformed output and never supplies substitute decisions. Temporary files are deleted after execution; the executable receives paths through environment variables, not interpolated shell commands.
# Step 2 marketing record contract

`LIMBAT01` reads 109-byte ASCII DISPLAY records from `DD_LIREQ` and writes 42-byte responses to `DD_LIRSP`, each newline-delimited. `LIMKREQ.cpy`, `LIMKRES.cpy` and `LIMKCTX.cpy` are authoritative. Both monetary amounts and limits are unsigned integer cents. These local files contain ordinal identifiers, financial facts and flags, not customer names or external IDs.

Request order: sequence (8), customer ordinal (8), campaign slot (2), eleven flags (population membership, current source, eligible/current underwriting, marketing consent, prescreen opt-out, suppression, campaign active/in-date, offer active/in-date), tenure-known (1), tenure (4), minimum tenure (4), product-held (1), exclude-held (1), amount-known (1), amount/minimum/maximum (11 each), prior-reservation-known (1), seconds since prior reservation (10), window count (5), campaign count (5), capacity (5), cooldown days (3), window maximum (5), existing-active-reservation flag (1). Boolean flags use Y/N; nullable consent/preferences/holdings use U for unknown.

Responses: sequence (8), Q/X status (1), rule version (12), 21 ordered reason bits (21). Reason order is documented in `marketing-qualification.md` and mapped by `CobolMarketingQualifier`. The adapter verifies count, sequence, length, status, rule version and reason consistency before accepting any results.

Inputs are grouped by customer ordinal, then campaign priority/ID, then offer ID. Campaign slots 1-20 are stable within a run. The explicit initialized context tracks per-campaign allocations and per-customer/current-campaign allocations. All qualifying offers in a customer/campaign group share one tentative reservation. The policy has no database, network or terminal I/O; the Java transaction publishes reservations only after finalization succeeds.

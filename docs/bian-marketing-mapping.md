<!-- Maps demonstration marketing concepts to BIAN references without treating BIAN as a source of credit thresholds. -->
# BIAN reference mapping for Step 2

The referenced organization is **BIAN, Banking Industry Architecture Network**, at bian.org. This project consulted the official release **14.0.0** models on 2026-09-16. They provide domain and interaction vocabulary for this implementation. They are not a source of universal credit thresholds, numeric marketing frequency caps or a legally sufficient US consent policy.

| Local concept | Official reference | Implemented interpretation |
| --- | --- | --- |
| Versioned campaigns and selection criteria | Customer Campaign Design | Campaign revisions contain active dates, priority, offer references and selection controls. |
| Qualification run and candidate outcomes | Customer Campaign Execution, including CandidateSelection | A run owns immutable candidate outcomes and explicit preview/finalize/cancel actions. Campaign delivery remains a later step. |
| Product/offer qualification outcome | Customer Product And Service Eligibility, including EligibilityCheck | Each customer/offer result records eligibility, reasons and product/assessment references. |
| Product already held | CustomerProductandServiceTypeUsage in the eligibility model | Campaigns may exclude an existing product; unknown data is treated explicitly. |
| Risk ID and source lineage | Local extension | A per-customer/per-run correlation key joins campaign outcomes and reservations to exact customer and underwriting records. This is not a BIAN credit-risk assessment. |

The local `/api/v1/marketing` contract intentionally uses smaller application-specific DTOs. It does not reproduce the official endpoint names and full ISO 20022 object structures, and it does not claim BIAN certification or drop-in API conformance. The mapping and domain boundaries are implemented; the complete standard is not imported into a local demonstration.

The policy `MKT-2026-001`, explicit opt-in requirement, suppression semantics, 7-day cooldown, 30-day window, quota values, tenure/ownership checks and illustrative offer terms are **versioned demonstration choices**. They are not presented as BIAN-mandated business rules. ML ranking can later select among qualified candidates without bypassing these gates.

Primary sources:

- [BIAN 14 official release](https://github.com/bian-official/public/tree/main/release14.0.0)
- [Customer Campaign Design](https://raw.githubusercontent.com/bian-official/public/main/release14.0.0/apis-iso20022_ext-ddd/oas3/yamls/CustomerCampaignDesign.yaml)
- [Customer Campaign Execution](https://raw.githubusercontent.com/bian-official/public/main/release14.0.0/apis-iso20022_ext-ddd/oas3/yamls/CustomerCampaignExecution.yaml)
- [Customer Product And Service Eligibility](https://raw.githubusercontent.com/bian-official/public/main/release14.0.0/apis-iso20022_ext-ddd/oas3/yamls/CustomerProductAndServiceEligibility.yaml)

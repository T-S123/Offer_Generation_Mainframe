# Structured contracts and interaction examples

**Proposed contracts, version 1.0.0.** These describe future interfaces; they are not implemented APIs. All six agents run in separate Java microservices and use GPT-6 Astra.

## Common rules

Use operation-specific JSON Schemas and Java records. Set additionalProperties=false on controlled objects. Explicitly declare required fields, enum values, numeric limits, units and maximum array/text sizes. Optional model-output properties are represented as required nullable values where required by OpenAI strict schemas. Domain records have their own versions; do not reuse A2A's protocol version as the business schema version.

Common request envelope:

| Field | Type / meaning |
|---|---|
| schemaVersion | Semantic version of this operation's business contract |
| kind | Fixed operation enum, not arbitrary agent instructions |
| requestId | Caller-scoped idempotency identity |
| workflowId, stepId, parentStepId | Durable workflow identity; parent nullable for root |
| planRevision | Integer ≥1; rejects stale scope/budget |
| actor | {analystId, tenantId, servicePrincipal}; verified from transport credentials, never trusted just because present |
| traceId | Correlation identity, not authorization |
| baselineRef, scopeRef | Immutable artifact references; nullable only for initial intent |
| budgetLeaseId, deadline | Server-validated resource authority and ISO timestamp |
| payload | Typed operation-specific object |
| evidenceRefs | Bounded immutable references, empty if none |

An artifact reference includes artifactId, kind, schemaVersion, sha256 and ownerService; retrieval occurs through an authorized API. A reference is not a bearer credential. Large tables and raw rows stay in artifacts; agent responses include counts and bounded summaries.

Common result envelope: schemaVersion, kind, requestId, workflowId, stepId, planRevision, status (SUCCEEDED, NEEDS_INPUT, PARTIAL, FAILED), artifactRefs, summary, limitations[], provenance (agentId, modelId, promptVersion, toolReceiptIds, producedAt), payload (typed operation-specific result), error (nullable typed error). The application status is distinct from the A2A TaskState.

Each error includes code, category, retryable, message, affectedParameterIds, requiredAction and evidenceRefs. Categories: VALIDATION, SCOPE, EVIDENCE, AUTH, DATA_QUALITY, DEPENDENCY, BUDGET, CONFLICT. No raw stack trace or secrets are sent to the model/UI.

## Six agent operation contracts

The common envelope applies to every row. Lists are bounded; full data goes in referenced artifacts. “No access” is enforced by the tool gateway.

| Agent | Required payload input | Required payload output | No access |
|---|---|---|---|
| Orchestrator | intentText:string, intentType:enum, conversationId, draftRef?, publicationRef?, requestedLocks:string[], userBudget? | resolvedIntent, workflowPlanRef?, clarification:{question,choices,affectedPaths}?, answer:{text,claimRefs}, nextAction:enum | Direct DB, customer selection, direct publish |
| Research | baselineRef, parameterIds:string[], product, geography, populationProfileRef?, policyRefs, asOf, allowedSourceClasses | claims:[{claimId,parameterId,proposedDomain,sourceRefs,applicability,confidenceCategory,conflicts}], evidenceBundleRef (nullable when evidence is unavailable), unsupportedParameterIds | Draft/run/publication writes; unrestricted customer rows |
| Designer | baselineRef, authorizedScopeRef, evidenceBundleRef, metricContractRef, budgetConfig, discoveryPopulationRef, validationPopulationRef | domains[], canonicalLockedPaths[], baselineInvariantHash, candidateStrategy, allocation, objectiveDefinitions[], stopRules, searchPlanRef | Live catalog writes; scope expansion |
| Coordinator | approvedSearchPlanRef, scopeRef, engineContext, budgetLeaseId, resumeCursor? | evaluations:[{candidateId,runId?,status,resultRef?,failure?}], progress:{started,completed,failed,cached,remaining}, seriesRef, stopReason | Publication, modifying unauthorized fields |
| Analyzer | analysisMode:SIMULATION or FEEDBACK, resultIndexRef?, metricSnapshotRef?, predictionRef?, metricContractRef, permittedSegmentIds | winners:{acceptanceCandidateId?,eligibilityCandidateId?}, frontierRef, facts[], associations[], hypotheses[], comparability, limitations, recommendedNextChecks | Inventing metrics; changing data/labels/rules; causal certainty from association |
| Reflection | subjectRefs[], reviewType:PLAN or RESULTS or FEEDBACK, scopeRef, metricContractRef, evidenceRefs | verdict:PASS or REVISE or INSUFFICIENT_EVIDENCE, findings:[{code,severity,claimRef,evidenceRefs,requiredCheck}], correctionRequest?, approvalRequired:boolean | Publication approval; rewriting historical predictions |

### Domain types

- **AuthorizedScope:** allowedParameterIds, lockedStages, fullBaselineHash, businessInvariantHash, effectiveDomains, constraints, metricContractRef, budgetConfig, scopeRevision, analystConfirmationRef. A stage lock overrides any allowed path.
- **RangeDomain:** parameterId, type, unit, low?, high?, step?, values?, baselineValue, sentinelPermissions, policyRefs, evidenceRefs, applicability, confidenceCategory, rationaleSummary. Continuous bounds are illegal for booleans and metadata.
- **SearchPlan:** population/date/seed/version references, mixed-domain strategy, candidate/execution/LLM limits, discovery/refinement/validation allocation, objective floors and support rules, de-duplication policy, stop rules.
- **Candidate:** candidateId, parentCandidateIds, canonicalPatch, fullConfigurationRef, configurationHash, scopeRevision, round, reason, expectedMetricKind, draftRef, evaluationRefs. A proposal may contain no metric until execution computes it.
- **Evaluation:** evaluationId, candidateId, attempt, engineRunRef, inputHash, state, N, E, simulatedExpectedSelections, simulatedAcceptanceAmongEligiblePct?, eligibilityPct?, scoredRowRef, cost/payment summaries, data/model/scoring versions, errors.
- **MetricContract:** scope (CATALOG_TERMS_ONLY or OFFER_FAMILY), acceptanceEvent=VALID_SELECTION, denominator=ELIGIBLE_CUSTOMERS, enrollmentRule, followUpDuration, expiryRule, entryWindow, asOf, sourceMode, dedupPolicy, censoringPolicy, predictionKind.
- **Claim:** claimId, claimType (MEASURED, SIMULATED, ASSOCIATION, HYPOTHESIS), text, metricRefs, cohortRefs, evidenceRefs, limitations. A HYPOTHESIS never becomes a MEASURED fact because another agent repeats it.
- **PredictionSnapshot:** candidate/run refs, numeric estimate, metricContract, predictionKind (SIMULATED_UTILITY or CALIBRATED_SELECTION), model/scoring version, population/scope hashes, generatedAt, interval and intervalType?, knownOmissions. Immutable.
- **PublicationPreview:** previewId, candidateId, finalRunId, draftVersion, completeTermsAndRulesRef, exactDiff, scopeRevision, baselineCatalogVersions, reportRefs, reviewNote, hash, expiresAt. Human confirmation is captured outside agent output.
- **PublicationReceiptLink:** previewId, analystId, approvalId, requestHash, engineReceipt, offerId, campaignId, ruleVersion, committedAt, predictionSnapshotIds, predecessorPublicationId?.
- **ObservedMetricSnapshot:** cohortId, publication/offer/terms identity, E, Y, acceptancePct?, N?, eligibilityPct?, provisionalCount, maturity, coverage, sourceWatermarks, asOf, revision, sourceMode, correctionRefs.
- **FeedbackReport:** immutablePredictionRef, observedMetricRef, comparability (MATCH, DIAGNOSTIC_ONLY, TARGET_MISMATCH, INCOMPLETE_DATA), gapPercentagePoints?, decompositionRef, measuredFacts[], supportedAssociations[], hypotheses[], researchRefs, nextExperimentScope?, lockedStages.

Represent monetary values in business envelopes as canonical decimal strings plus units to avoid binary-rounding ambiguity. Adapters convert to the existing engine's decimal JSON-number contracts using BigDecimal and validate precision. Typed threshold integers remain integers. Rates explicitly identify percentage versus probability.

## Illustrative A2A exchange

[examples.json](examples.json) contains a v1 SendMessage request and a Task response requesting additional input carrying structured data. These are synthetic architecture examples with non-resolvable demo IDs, not runnable production fixtures. The nested business operation is ResearchRequest, not a custom replacement for A2A's method name.

The Task artifact carries the validated ResearchResult. For longer research, return task status updates and later an artifact. Requests for analyst clarification use the task's input-required state and a typed Clarification artifact/message. Task completion is not catalog publication.

The Agent Card should list service identity, version, supportedInterfaces, capabilities, security requirements, and the skill's description/input/output media types. Only advertise capabilities that the deployed server implements. The selected A2A SDK release and generated protocol schema are checked against the official v1.0 definitions during implementation; do not mix tasks/send (local prototype), message/send (older releases) and SendMessage (target v1).

## Tool specifications

Every registered tool descriptor includes:
- name, toolVersion, description, inputSchema, outputSchema.
- sideEffect: READ, ISOLATED_SIMULATION_WRITE, PUBLICATION_WRITE.
- requiredPermission and allowedAgentRoles.
- dataClass, redaction policy, authorized resource selectors.
- timeout, maxResultBytes, rate limit, idempotency policy.
- provenance fields returned on success/failure.

Example tool semantics:
- **simulation.submitCandidate:** validates scope/configuration hash, reserves an evaluation, creates/loads the candidate's isolated draft through idempotent engine admission, submits/looks up a run, returns durable references.
- **metrics.getOfferPerformance:** takes publication ID plus MetricContract and as-of; returns computed numerator/denominator, freshness and comparability, not free-form SQL.
- **research.searchPolicies:** searches approved versions, returns excerpts/source IDs/effective dates/applicability; content cannot grant permissions.
- **publication.confirm:** available only to the authenticated interaction service, requires preview hash and analyst token, returns engine receipt or an explicit uncertain/conflict state.

All tools return structured results with toolReceiptId, requestHash, sourceTimestamp and sourceVersion where available. Agent natural-language narration never substitutes for a successful tool receipt.

## Data event proposals

The existing qualification/storage events remain authoritative source contracts. Proposed new AI-owned event envelope: eventId, eventType, schemaVersion, aggregateId, aggregateVersion, occurredAt, ingestedAt, traceId, sourceMode, payloadRef/hash.

Examples: AI_SCOPE_CONFIRMED, AI_CANDIDATE_ADMITTED, AI_EVALUATION_COMPLETED, AI_ANALYSIS_READY, AI_PUBLICATION_LINKED, OUTCOME_COHORT_REVISED, FEEDBACK_REVIEW_REQUESTED. These are implementation proposals; no current topic is claimed to exist.

New source events needed for complete metrics include ASSESSMENT_ADMITTED and OFFER_PRESENTED with exact identity/version/time. Expose these through owner services and transactional outboxes where the underlying write is transactional. No distributed dual write is treated as atomic.

## Contract evolution and checks

Additive contract revisions remain readable; breaking schemas get a new major version/skill declaration and explicit compatibility check. Reject unknown parameter IDs, invalid units, stale scope references, unreachable/unauthorized artifact references and altered hashes. Ensure external MCP schemas and OpenAI tool/output schemas are compatible at the adapter, rather than assuming one schema works unmodified across all protocols.

Schema validation, authoritative rule validation, arithmetic verification, evidence existence checks and access control are independent gates. Agents can critique one another, but deterministic invariants remain mandatory regardless of their agreement.

      * Applies CEX-2026-001 consent, suppression, channel and
      * outbound-frequency rules to a final campaign package.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LIEX01C.
       DATA DIVISION.
       LINKAGE SECTION.
       COPY LIEXREQ.
       01 LI-RESULT                     PIC X(32).
       PROCEDURE DIVISION USING LI-EXECUTION-REQUEST LI-RESULT.
      * Checks current authority and delivery permissions before
      * applying outbound cooldown and frequency limits.
       0000-QUALIFY-EXECUTION.
           MOVE 'APPROVED' TO LI-RESULT
           EVALUATE TRUE
             WHEN LI-CONSENT NOT = 'Y'
               MOVE 'MARKETING_CONSENT_REQUIRED' TO LI-RESULT
             WHEN LI-OPT-OUT NOT = 'N'
               MOVE 'PRESCREEN_OPT_OUT_OR_UNKNOWN' TO LI-RESULT
             WHEN LI-SUPPRESSED = 'Y'
               MOVE 'GLOBAL_SUPPRESSION' TO LI-RESULT
             WHEN LI-QUALIFIED NOT = 'Y'
               MOVE 'OFFER_NOT_CURRENT' TO LI-RESULT
             WHEN LI-CHANNEL NOT = 'Y'
               MOVE 'NO_ENABLED_CHANNEL' TO LI-RESULT
             WHEN LI-SAME-DISPATCH NOT = 'Y'
               AND LI-ELAPSED < LI-COOLDOWN
               MOVE 'EXECUTION_COOLDOWN' TO LI-RESULT
             WHEN LI-SAME-DISPATCH NOT = 'Y'
               AND LI-COUNT >= LI-LIMIT
               MOVE 'EXECUTION_WINDOW_LIMIT' TO LI-RESULT
           END-EVALUATE
           GOBACK.

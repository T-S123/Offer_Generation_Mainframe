      * Applies MKT-2026-001 marketing qualification and throttling
      * rules with explicit customer and campaign allocation context.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LIMK01C.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-SECONDS                   PIC 9(10).
       01 WS-TOTAL                     PIC 9(6).
       LINKAGE SECTION.
       COPY LIMKREQ.
       COPY LIMKRES.
       COPY LIMKCTX.
       PROCEDURE DIVISION USING LI-MK-REQUEST LI-MK-RESPONSE
                                LI-MK-CONTEXT.
      * Evaluates qualification before throttling and allocates one slot
      * per qualified customer and campaign group.
       0000-QUALIFY.
           INITIALIZE LI-MK-RESPONSE
           MOVE MK-SEQUENCE TO MR-SEQUENCE
           MOVE 'MKT-2026-001' TO MR-POLICY
           MOVE 'X' TO MR-STATUS
           IF MK-CUSTOMER NOT = MC-LAST-CUSTOMER
               MOVE MK-CUSTOMER TO MC-LAST-CUSTOMER
               MOVE ZERO TO MC-CUSTOMER-ADDED MC-LAST-CAMPAIGN
           END-IF
           IF MK-CAMPAIGN NOT = MC-LAST-CAMPAIGN
               MOVE MK-CAMPAIGN TO MC-LAST-CAMPAIGN
               MOVE ZERO TO MC-GROUP-GRANTED
           END-IF
           PERFORM 1000-QUALIFICATION
           IF MR-REASONS = ALL '0'
               PERFORM 2000-THROTTLING
           END-IF
           IF MR-REASONS = ALL '0'
               MOVE 'Q' TO MR-STATUS
               IF MC-GROUP-GRANTED = ZERO
                   ADD 1 TO MC-CUSTOMER-ADDED
                   ADD 1 TO MC-CAMPAIGN-ADDED(MK-CAMPAIGN)
                   MOVE 1 TO MC-GROUP-GRANTED
               END-IF
           END-IF
           GOBACK.
      * Checks source freshness, underwriting, consent, suppressions,
      * campaign criteria and offer amount bounds.
       1000-QUALIFICATION.
           IF MK-MEMBER NOT = 'Y' MOVE 1 TO MR-REASON(1)
           END-IF
           IF MK-SOURCE-CURRENT NOT = 'Y'
               MOVE 1 TO MR-REASON(2) END-IF
           IF MK-UW-ELIGIBLE NOT = 'Y' MOVE 1 TO MR-REASON(3)
           END-IF
           IF MK-UW-CURRENT NOT = 'Y' MOVE 1 TO MR-REASON(4)
           END-IF
           IF MK-CONSENT NOT = 'Y' MOVE 1 TO MR-REASON(5)
           END-IF
           IF MK-OPT-OUT NOT = 'N' MOVE 1 TO MR-REASON(6)
           END-IF
           IF MK-SUPPRESSED = 'Y' MOVE 1 TO MR-REASON(7)
           END-IF
           IF MK-CAMPAIGN-ACTIVE NOT = 'Y'
               MOVE 1 TO MR-REASON(8) END-IF
           IF MK-CAMPAIGN-DATE NOT = 'Y'
               MOVE 1 TO MR-REASON(9) END-IF
           IF MK-OFFER-ACTIVE NOT = 'Y'
               MOVE 1 TO MR-REASON(10) END-IF
           IF MK-OFFER-DATE NOT = 'Y'
               MOVE 1 TO MR-REASON(11) END-IF
           IF MK-MIN-TENURE > ZERO
               IF MK-TENURE-KNOWN NOT = 'Y'
                   MOVE 1 TO MR-REASON(12)
               ELSE
                   IF MK-TENURE < MK-MIN-TENURE
                       MOVE 1 TO MR-REASON(13) END-IF
               END-IF
           END-IF
           IF MK-EXCLUDE-HELD = 'Y'
               IF MK-PRODUCT-HELD = 'Y'
                   MOVE 1 TO MR-REASON(14) END-IF
               IF MK-PRODUCT-HELD = 'U'
                   MOVE 1 TO MR-REASON(15) END-IF
           END-IF
           IF MK-AMOUNT-KNOWN NOT = 'Y'
               MOVE 1 TO MR-REASON(16)
           ELSE
               IF MK-AMOUNT < MK-MIN-AMOUNT OR
                  MK-AMOUNT > MK-MAX-AMOUNT
                   MOVE 1 TO MR-REASON(17) END-IF
           END-IF
           IF MK-ALREADY-RESERVED = 'Y'
               MOVE 1 TO MR-REASON(21) END-IF.
      * Checks cooldown, rolling customer frequency and campaign
      * capacity only for otherwise qualified groups.
       2000-THROTTLING.
           IF MC-GROUP-GRANTED = 1 EXIT PARAGRAPH END-IF
           COMPUTE WS-SECONDS = MK-COOLDOWN-DAYS * 86400
           IF (MK-PRIOR-KNOWN = 'Y' AND
               MK-PRIOR-SECONDS < WS-SECONDS) OR
              (MC-CUSTOMER-ADDED > ZERO AND
               MK-COOLDOWN-DAYS > ZERO)
               MOVE 1 TO MR-REASON(18)
           END-IF
           COMPUTE WS-TOTAL = MK-WINDOW-COUNT + MC-CUSTOMER-ADDED
           IF WS-TOTAL >= MK-WINDOW-MAX
               MOVE 1 TO MR-REASON(19) END-IF
           COMPUTE WS-TOTAL = MK-CAMPAIGN-COUNT +
                             MC-CAMPAIGN-ADDED(MK-CAMPAIGN)
           IF WS-TOTAL >= MK-CAPACITY
               MOVE 1 TO MR-REASON(20) END-IF.

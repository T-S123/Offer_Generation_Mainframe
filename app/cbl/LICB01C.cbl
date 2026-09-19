      * Applies the CBR-2026-001 demonstration credit policy to
      * independent bureau facts and requested loan terms.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LICB01C.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-MIN-SCORE                  PIC 9(03).
       01 WS-MAX-DTI                    PIC 9(03).
       01 WS-MAX-AMOUNT                 PIC 9(09)V99.
       01 WS-RATE                       USAGE COMP-2.
       01 WS-FACTOR                     USAGE COMP-2.
       01 WS-PAYMENT                    PIC 9(12)V99.
       LINKAGE SECTION.
       COPY LICBREQ.
       COPY LICBRES.
       PROCEDURE DIVISION USING LI-CB-REQUEST LI-CB-RESPONSE.
      * Determines approved, declined or review status using independent
      * bureau facts and product-specific policy limits.
       0000-QUALIFY-CREDIT.
           INITIALIZE LI-CB-RESPONSE
           MOVE 'CBR-2026-001' TO CB-POLICY
           MOVE ALL '0' TO CB-REASONS
           MOVE 'A' TO CB-OUTCOME
           IF CB-COMPLETE NOT = 'Y'
               MOVE '1' TO CB-MISSING
           END-IF
           IF CB-FILE-OK NOT = 'Y'
               MOVE '1' TO CB-FILE-REVIEW
           END-IF
           IF CB-REPORT-AGE > 30
               MOVE '1' TO CB-STALE
           END-IF
           IF CB-ACCOUNT-AGE < 6
               MOVE '1' TO CB-THIN
           END-IF
           IF CB-REASONS NOT = ALL '0'
               MOVE 'R' TO CB-OUTCOME
               GOBACK
           END-IF
           EVALUATE CB-PRODUCT
             WHEN 'PL'
               MOVE 680 TO WS-MIN-SCORE
               MOVE 45 TO WS-MAX-DTI
               MOVE 50000 TO WS-MAX-AMOUNT
             WHEN 'CC'
               MOVE 660 TO WS-MIN-SCORE
               MOVE 45 TO WS-MAX-DTI
               MOVE 25000 TO WS-MAX-AMOUNT
             WHEN 'AL'
               MOVE 640 TO WS-MIN-SCORE
               MOVE 50 TO WS-MAX-DTI
               MOVE 100000 TO WS-MAX-AMOUNT
             WHEN OTHER
               MOVE '1' TO CB-MISSING
               MOVE 'R' TO CB-OUTCOME
               GOBACK
           END-EVALUATE
           PERFORM 1000-COMPUTE-PAYMENT
           PERFORM 2000-CHECK-CREDIT
           IF CB-REASONS NOT = ALL '0'
               MOVE 'D' TO CB-OUTCOME
           END-IF
           GOBACK.
      * Calculates the proposed monthly payment used in debt
      * affordability checks.
       1000-COMPUTE-PAYMENT.
           EVALUATE TRUE
             WHEN CB-PRODUCT = 'CC'
               COMPUTE WS-PAYMENT ROUNDED = CB-AMOUNT * 0.03
             WHEN CB-TERM = 0
               MOVE '1' TO CB-MISSING
               MOVE CB-AMOUNT TO WS-PAYMENT
             WHEN CB-APR = 0
               COMPUTE WS-PAYMENT ROUNDED = CB-AMOUNT / CB-TERM
             WHEN OTHER
               COMPUTE WS-RATE = CB-APR / 1200
               COMPUTE WS-FACTOR = (1 + WS-RATE) ** CB-TERM
               COMPUTE WS-PAYMENT ROUNDED = CB-AMOUNT * WS-RATE
                   * WS-FACTOR / (WS-FACTOR - 1)
           END-EVALUATE.
      * Checks bureau score, debt, utilization, adverse history and
      * collateral against the selected policy.
       2000-CHECK-CREDIT.
           IF CB-SCORE < WS-MIN-SCORE
               MOVE '1' TO CB-LOW-SCORE
           END-IF
           IF CB-INCOME = 0 OR
               (CB-DEBT + WS-PAYMENT) * 100 > CB-INCOME *
               WS-MAX-DTI
               MOVE '1' TO CB-HIGH-DTI
           END-IF
           IF CB-UTIL > 90 MOVE '1' TO CB-HIGH-UTIL END-IF
           IF CB-DELINQ > 2 MOVE '1' TO CB-HIGH-DELINQ END-IF
           IF CB-INQUIRIES > 6 MOVE '1' TO CB-HIGH-INQUIRY END-IF
           IF CB-BANKRUPT = 'Y' MOVE '1' TO CB-BANKRUPTCY END-IF
           IF CB-AMOUNT = 0 OR CB-AMOUNT > WS-MAX-AMOUNT
               MOVE '1' TO CB-HIGH-AMOUNT
           END-IF
           IF CB-PRODUCT = 'AL' AND
               (CB-VEHICLE = 0 OR CB-AMOUNT * 100 >
                CB-VEHICLE * 110)
               MOVE '1' TO CB-HIGH-LTV
           END-IF.

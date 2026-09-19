      * Evaluates versioned Business rule forms for underwriting,
      * marketing and bureau qualification. Marketing consent and
      * suppression gates remain mandatory.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LIRL01C.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-RATE                      USAGE COMP-2.
       01 WS-FACTOR                    USAGE COMP-2.
       01 WS-PAYMENT                   PIC 9(12)V99.
       LINKAGE SECTION.
       COPY LIRLREQ.
       01 RL-RESPONSE.
          05 RL-STATUS                 PIC X.
          05 RL-REASONS                PIC X(19).
       PROCEDURE DIVISION USING RL-REQUEST RL-RESPONSE.
      * Evaluates the requested rule stage and records threshold or
      * mandatory-gate failures.
       0000-EVALUATE.
           MOVE 'E' TO RL-STATUS
           MOVE ALL '0' TO RL-REASONS
           MOVE ZERO TO WS-PAYMENT
           IF RL-COMPLETE NOT = 'Y'
               MOVE '1' TO RL-REASONS(1:1)
               MOVE 'I' TO RL-STATUS
               GOBACK
           END-IF
           IF RL-STAGE = 'B'
               IF RL-MATCHED NOT = 'Y'
                   MOVE '1' TO RL-REASONS(2:1) END-IF
               IF RL-REPORT-AGE > RP-REPORT-AGE
                   MOVE '1' TO RL-REASONS(3:1) END-IF
               IF RL-HISTORY < RP-HISTORY
                   MOVE '1' TO RL-REASONS(4:1) END-IF
               PERFORM 1000-PAYMENT
           END-IF
           IF RL-INCOME < RP-INCOME
               MOVE '1' TO RL-REASONS(5:1) END-IF
           IF RL-SCORE < RP-SCORE
               MOVE '1' TO RL-REASONS(6:1) END-IF
           IF (RL-DEBT + WS-PAYMENT) * 100 >
               RL-INCOME * RP-DTI OR
               (RL-STAGE NOT = 'M' AND RL-INCOME = ZERO)
               MOVE '1' TO RL-REASONS(7:1) END-IF
           IF RL-UTIL > RP-UTIL
               MOVE '1' TO RL-REASONS(8:1) END-IF
           IF RL-DELINQ > RP-DELINQ
               MOVE '1' TO RL-REASONS(9:1) END-IF
           IF RL-AMOUNT = ZERO OR RL-AMOUNT > RP-AMOUNT OR
              (RL-STAGE NOT = 'B' AND
               RL-AMOUNT > RL-INCOME * RP-MULTIPLE)
               MOVE '1' TO RL-REASONS(10:1) END-IF
           IF RL-PRODUCT = 'AL'
               IF RL-VEHICLE-AGE > RP-VEHICLE-AGE
                   MOVE '1' TO RL-REASONS(11:1) END-IF
               IF RL-VEHICLE = ZERO OR RL-AMOUNT * 100 >
                   RL-VEHICLE * RP-LTV
                   MOVE '1' TO RL-REASONS(12:1) END-IF
           END-IF
           IF RL-TENURE < RP-TENURE
               MOVE '1' TO RL-REASONS(13:1) END-IF
           IF RP-EXCLUDE = 'Y' AND RL-HELD = 'Y'
               MOVE '1' TO RL-REASONS(14:1) END-IF
           IF RL-INQUIRIES > RP-INQUIRIES
               MOVE '1' TO RL-REASONS(15:1) END-IF
           IF RL-BANKRUPT = 'Y' AND RP-BANKRUPTCY NOT = 'Y'
               MOVE '1' TO RL-REASONS(16:1) END-IF
           IF RL-STAGE = 'M'
               IF RL-CONSENT NOT = 'Y'
                   MOVE '1' TO RL-REASONS(17:1) END-IF
               IF RL-OPT-OUT NOT = 'N'
                   MOVE '1' TO RL-REASONS(18:1) END-IF
               IF RL-SUPPRESSED = 'Y'
                   MOVE '1' TO RL-REASONS(19:1) END-IF
           END-IF
           IF RL-REASONS NOT = ALL '0' MOVE 'D' TO RL-STATUS
           END-IF
           IF RL-STAGE = 'B' AND RL-REASONS(2:3) NOT = '000'
               MOVE 'I' TO RL-STATUS END-IF
           GOBACK.
      * Calculates the proposed card or installment payment for the
      * bureau-stage affordability check.
       1000-PAYMENT.
           EVALUATE TRUE
             WHEN RL-PRODUCT = 'CC'
               COMPUTE WS-PAYMENT ROUNDED = RL-AMOUNT * 0.03
             WHEN RL-TERM = ZERO
               MOVE RL-AMOUNT TO WS-PAYMENT
             WHEN RL-APR = ZERO
               COMPUTE WS-PAYMENT ROUNDED = RL-AMOUNT / RL-TERM
             WHEN OTHER
               COMPUTE WS-RATE = RL-APR / 1200
               COMPUTE WS-FACTOR = (1 + WS-RATE) ** RL-TERM
               COMPUTE WS-PAYMENT ROUNDED = RL-AMOUNT * WS-RATE
                   * WS-FACTOR / (WS-FACTOR - 1)
           END-EVALUATE.

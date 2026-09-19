      * Evaluates the SIM-2026-001 demonstration underwriting rules for
      * personal loans, credit cards and auto loans without
      * infrastructure access.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LIUW01C.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-MIN-INCOME                 PIC 9(07)V99.
       01 WS-MIN-SCORE                  PIC 9(03).
       01 WS-MAX-DTI                    PIC 9(03).
       01 WS-MAX-AMOUNT                 PIC 9(09)V99.
       01 WS-INCOME-MULTIPLE            PIC 9(02).
       LINKAGE SECTION.
       COPY LIUWREQ.
       COPY LIUWRES.
       PROCEDURE DIVISION USING LI-UW-REQUEST LI-UW-RESPONSE.
      * Selects product thresholds and returns eligibility with reason
      * flags for the supplied customer facts.
       0000-EVALUATE-CUSTOMER.
           INITIALIZE LI-UW-RESPONSE
           MOVE LI-REQUEST-KEY TO LI-RESPONSE-KEY
           MOVE LI-PRODUCT TO LI-RESPONSE-PRODUCT
           MOVE 'SIM-2026-001' TO LI-POLICY-VERSION
           MOVE ALL '0' TO LI-REASONS
           MOVE 'E' TO LI-DECISION
           EVALUATE LI-PRODUCT
             WHEN 'PL'
               MOVE 2000 TO WS-MIN-INCOME
               MOVE 660 TO WS-MIN-SCORE
               MOVE 40 TO WS-MAX-DTI
               MOVE 50000 TO WS-MAX-AMOUNT
               MOVE 24 TO WS-INCOME-MULTIPLE
             WHEN 'CC'
               MOVE 1500 TO WS-MIN-INCOME
               MOVE 640 TO WS-MIN-SCORE
               MOVE 45 TO WS-MAX-DTI
               MOVE 25000 TO WS-MAX-AMOUNT
               MOVE 6 TO WS-INCOME-MULTIPLE
             WHEN 'AL'
               MOVE 1800 TO WS-MIN-INCOME
               MOVE 620 TO WS-MIN-SCORE
               MOVE 45 TO WS-MAX-DTI
               MOVE 100000 TO WS-MAX-AMOUNT
               MOVE 36 TO WS-INCOME-MULTIPLE
             WHEN OTHER
               MOVE '1' TO LI-BAD-PRODUCT
               MOVE 'I' TO LI-DECISION
               GOBACK
           END-EVALUATE
           PERFORM 1000-CHECK-COMPLETENESS
           IF LI-MISSING-DATA = '1'
               MOVE 'I' TO LI-DECISION
               GOBACK
           END-IF
           PERFORM 2000-CHECK-COMMON-POLICY
           IF LI-PRODUCT = 'CC' AND LI-UTILIZATION > 80
               MOVE '1' TO LI-HIGH-UTIL
           END-IF
           IF LI-PRODUCT = 'AL'
               PERFORM 3000-CHECK-VEHICLE
           END-IF
           IF LI-REASONS NOT = ALL '0'
               MOVE 'D' TO LI-DECISION
           END-IF
           GOBACK.
      * Marks missing product-specific facts so incomplete input cannot
      * receive an eligible decision.
       1000-CHECK-COMPLETENESS.
           IF LI-INCOME-KNOWN NOT = 'Y' OR
              LI-DEBT-KNOWN NOT = 'Y' OR
              LI-SCORE-KNOWN NOT = 'Y' OR
              LI-DELINQ-KNOWN NOT = 'Y' OR
              LI-AMOUNT-KNOWN NOT = 'Y'
               MOVE '1' TO LI-MISSING-DATA
           END-IF
           IF LI-PRODUCT = 'CC' AND LI-UTIL-KNOWN NOT = 'Y'
               MOVE '1' TO LI-MISSING-DATA
           END-IF
           IF LI-PRODUCT = 'AL' AND
              (LI-VEHICLE-KNOWN NOT = 'Y' OR
               LI-AGE-KNOWN NOT = 'Y')
               MOVE '1' TO LI-MISSING-DATA
           END-IF.
      * Checks income, score, debt, delinquency and requested amount
      * against the selected product thresholds.
       2000-CHECK-COMMON-POLICY.
           IF LI-MONTHLY-INCOME < WS-MIN-INCOME
               MOVE '1' TO LI-LOW-INCOME
           END-IF
           IF LI-CREDIT-SCORE < WS-MIN-SCORE
               MOVE '1' TO LI-LOW-SCORE
           END-IF
           IF LI-MONTHLY-DEBT * 100 >
              LI-MONTHLY-INCOME * WS-MAX-DTI
               MOVE '1' TO LI-HIGH-DEBT
           END-IF
           IF LI-DELINQUENCIES > 1
               MOVE '1' TO LI-HIGH-DELINQ
           END-IF
           IF LI-REQUESTED-AMOUNT = 0 OR
              LI-REQUESTED-AMOUNT > WS-MAX-AMOUNT OR
              LI-REQUESTED-AMOUNT >
              LI-MONTHLY-INCOME * WS-INCOME-MULTIPLE
               MOVE '1' TO LI-HIGH-AMOUNT
           END-IF.
      * Checks vehicle age and loan-to-value limits for an auto loan.
       3000-CHECK-VEHICLE.
           IF LI-VEHICLE-AGE > 12
               MOVE '1' TO LI-OLD-VEHICLE
           END-IF
           IF LI-VEHICLE-VALUE = 0 OR
              LI-REQUESTED-AMOUNT * 100 > LI-VEHICLE-VALUE * 110
               MOVE '1' TO LI-HIGH-LTV
           END-IF.

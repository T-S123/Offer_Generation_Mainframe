      * Checks personalized terms against the MOF-2026-001 catalog
      * envelope before separate underwriting and bureau
      * requalification.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LIOF01C.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-CHANGE                     PIC 9(09)V99.
       LINKAGE SECTION.
       COPY LIOFREQ.
       01 LI-RESULT                     PIC X(32).
       PROCEDURE DIVISION USING LI-OFFER-REQUEST LI-RESULT.
      * Checks proposed amount, APR, fee and term changes against the
      * permitted personalization envelope.
       0000-QUALIFY-VARIANT.
           MOVE 'APPROVED' TO LI-RESULT
           COMPUTE WS-CHANGE =
               LI-BASE-AMOUNT * LI-AMOUNT-CHANGE / 100
           EVALUATE TRUE
             WHEN LI-NEW-AMOUNT < LI-MIN-AMOUNT
             WHEN LI-NEW-AMOUNT > LI-MAX-AMOUNT
             WHEN FUNCTION ABS(LI-NEW-AMOUNT - LI-BASE-AMOUNT)
                  > WS-CHANGE
               MOVE 'AMOUNT_OUTSIDE_ENVELOPE' TO LI-RESULT
             WHEN LI-NEW-APR > LI-BASE-APR
             WHEN LI-NEW-APR < LI-MIN-APR
             WHEN LI-BASE-APR - LI-NEW-APR > LI-MAX-DISCOUNT
               MOVE 'APR_OUTSIDE_ENVELOPE' TO LI-RESULT
             WHEN LI-NEW-FEE > LI-BASE-FEE
               MOVE 'FEE_OUTSIDE_ENVELOPE' TO LI-RESULT
             WHEN LI-PRODUCT = 'CC' AND LI-NEW-TERM NOT = ZERO
               MOVE 'CARD_TERM_MUST_BE_ZERO' TO LI-RESULT
             WHEN LI-PRODUCT NOT = 'CC' AND
                 (LI-NEW-TERM < LI-MIN-TERM OR
                  LI-NEW-TERM > LI-MAX-TERM OR
                  FUNCTION ABS(LI-NEW-TERM - LI-BASE-TERM)
                      > LI-TERM-CHANGE)
               MOVE 'TERM_OUTSIDE_ENVELOPE' TO LI-RESULT
           END-EVALUATE
           GOBACK.

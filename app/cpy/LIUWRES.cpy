      * Defines immutable underwriting output with eligible, ineligible
      * or incomplete status and ordered reason flags.
       01 LI-UW-RESPONSE.
          05 LI-RESPONSE-KEY            PIC 9(08).
          05 LI-RESPONSE-PRODUCT        PIC X(02).
          05 LI-DECISION               PIC X.
          05 LI-POLICY-VERSION         PIC X(12).
          05 LI-REASONS.
             10 LI-MISSING-DATA         PIC X.
             10 LI-LOW-INCOME           PIC X.
             10 LI-LOW-SCORE            PIC X.
             10 LI-HIGH-DEBT            PIC X.
             10 LI-HIGH-DELINQ          PIC X.
             10 LI-HIGH-UTIL            PIC X.
             10 LI-HIGH-AMOUNT          PIC X.
             10 LI-OLD-VEHICLE          PIC X.
             10 LI-HIGH-LTV             PIC X.
             10 LI-BAD-PRODUCT          PIC X.

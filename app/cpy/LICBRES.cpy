      * Defines the bureau decision, reason flags and calculated payment
      * returned by the credit policy.
       01 LI-CB-RESPONSE.
          05 CB-OUTCOME                PIC X.
          05 CB-POLICY                 PIC X(12).
          05 CB-REASONS.
             10 CB-MISSING            PIC X.
             10 CB-FILE-REVIEW        PIC X.
             10 CB-STALE              PIC X.
             10 CB-THIN               PIC X.
             10 CB-LOW-SCORE          PIC X.
             10 CB-HIGH-DTI           PIC X.
             10 CB-HIGH-UTIL          PIC X.
             10 CB-HIGH-DELINQ        PIC X.
             10 CB-HIGH-INQUIRY       PIC X.
             10 CB-BANKRUPTCY         PIC X.
             10 CB-HIGH-LTV           PIC X.
             10 CB-HIGH-AMOUNT        PIC X.

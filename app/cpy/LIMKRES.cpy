      * Defines the marketing qualification status, policy version and
      * ordered exclusion and throttle reason flags.
       01 LI-MK-RESPONSE.
          05 MR-SEQUENCE               PIC 9(8).
          05 MR-STATUS                 PIC X.
          05 MR-POLICY                 PIC X(12).
          05 MR-REASONS.
             10 MR-REASON OCCURS 21    PIC 9.

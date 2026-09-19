      * Defines current consent, suppression, channel and dispatch-limit
      * facts for campaign execution checks.
       01 LI-EXECUTION-REQUEST.
          05 LI-CONSENT                 PIC X.
          05 LI-OPT-OUT                 PIC X.
          05 LI-SUPPRESSED              PIC X.
          05 LI-QUALIFIED               PIC X.
          05 LI-CHANNEL                 PIC X.
          05 LI-SAME-DISPATCH           PIC X.
          05 LI-ELAPSED                 PIC 9(10).
          05 LI-COOLDOWN                PIC 9(10).
          05 LI-COUNT                   PIC 9(05).
          05 LI-LIMIT                   PIC 9(05).

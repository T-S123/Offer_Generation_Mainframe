      * Defines marketing facts and versioned rule inputs using
      * fixed-width fields and integer USD cents.
       01 LI-MK-REQUEST.
          05 MK-SEQUENCE               PIC 9(8).
          05 MK-CUSTOMER               PIC 9(8).
          05 MK-CAMPAIGN               PIC 99.
          05 MK-MEMBER                 PIC X.
          05 MK-SOURCE-CURRENT         PIC X.
          05 MK-UW-ELIGIBLE            PIC X.
          05 MK-UW-CURRENT             PIC X.
          05 MK-CONSENT                PIC X.
          05 MK-OPT-OUT                PIC X.
          05 MK-SUPPRESSED             PIC X.
          05 MK-CAMPAIGN-ACTIVE        PIC X.
          05 MK-CAMPAIGN-DATE          PIC X.
          05 MK-OFFER-ACTIVE           PIC X.
          05 MK-OFFER-DATE             PIC X.
          05 MK-TENURE-KNOWN           PIC X.
          05 MK-TENURE                 PIC 9(4).
          05 MK-MIN-TENURE             PIC 9(4).
          05 MK-PRODUCT-HELD           PIC X.
          05 MK-EXCLUDE-HELD           PIC X.
          05 MK-AMOUNT-KNOWN           PIC X.
          05 MK-AMOUNT                 PIC 9(11).
          05 MK-MIN-AMOUNT             PIC 9(11).
          05 MK-MAX-AMOUNT             PIC 9(11).
          05 MK-PRIOR-KNOWN            PIC X.
          05 MK-PRIOR-SECONDS          PIC 9(10).
          05 MK-WINDOW-COUNT           PIC 9(5).
          05 MK-CAMPAIGN-COUNT         PIC 9(5).
          05 MK-CAPACITY               PIC 9(5).
          05 MK-COOLDOWN-DAYS          PIC 9(3).
          05 MK-WINDOW-MAX             PIC 9(5).
          05 MK-ALREADY-RESERVED       PIC X.

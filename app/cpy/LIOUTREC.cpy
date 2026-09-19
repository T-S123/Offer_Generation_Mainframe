      * Defines the fixed-block synthetic outbound marketing record
      * encoded as IBM037 without record delimiters.
       01 LI-OUTBOUND-RECORD.
          05 LO-PACKAGE-ID              PIC X(63).
          05 LO-PACKAGE-VERSION         PIC X(10).
          05 LO-CUSTOMER-ID             PIC X(36).
          05 LO-CAMPAIGN-ID             PIC X(80).
          05 LO-RESPONSE-ID             PIC X(80).
          05 LO-RESPONSE-VERSION        PIC X(10).
          05 LO-CATALOG-OFFER-ID        PIC X(80).
          05 LO-OFFER-KIND              PIC X(12).
          05 LO-LEAD                    PIC X.
          05 LO-CHANNEL                 PIC X(6).
          05 LO-SYNTHETIC-EMAIL         PIC X(80).
          05 LO-SYNTHETIC-PHONE         PIC X(16).
          05 LO-SYNTHETIC-POSTAL        PIC X(100).
          05 LO-AMOUNT-USD              PIC X(14).
          05 LO-APR-PCT                 PIC X(8).
          05 LO-ANNUAL-FEE-USD          PIC X(14).
          05 LO-TERM-MONTHS             PIC X(3).
          05 LO-VALID-UNTIL             PIC X(30).

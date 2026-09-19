      * Defines the per-run allocation counters shared by ordered
      * marketing requests without persisting them inside COBOL.
       01 LI-MK-CONTEXT.
          05 MC-LAST-CUSTOMER          PIC 9(8).
          05 MC-LAST-CAMPAIGN          PIC 99.
          05 MC-CUSTOMER-ADDED         PIC 9(5).
          05 MC-GROUP-GRANTED          PIC 9.
          05 MC-CAMPAIGN-ADDED OCCURS 20 PIC 9(5).

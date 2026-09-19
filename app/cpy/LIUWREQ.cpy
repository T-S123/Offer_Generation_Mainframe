      * Defines underwriting input fields and presence flags that
      * distinguish missing facts from zero. Monetary fields use an
      * implied two-place decimal.
       01 LI-UW-REQUEST.
          05 LI-REQUEST-KEY             PIC 9(08).
          05 LI-PRODUCT                 PIC X(02).
          05 LI-INCOME-KNOWN            PIC X.
          05 LI-MONTHLY-INCOME          PIC 9(09)V99.
          05 LI-DEBT-KNOWN              PIC X.
          05 LI-MONTHLY-DEBT            PIC 9(09)V99.
          05 LI-SCORE-KNOWN             PIC X.
          05 LI-CREDIT-SCORE            PIC 9(03).
          05 LI-DELINQ-KNOWN            PIC X.
          05 LI-DELINQUENCIES           PIC 9(02).
          05 LI-UTIL-KNOWN              PIC X.
          05 LI-UTILIZATION             PIC 9(03)V99.
          05 LI-AMOUNT-KNOWN            PIC X.
          05 LI-REQUESTED-AMOUNT        PIC 9(09)V99.
          05 LI-VEHICLE-KNOWN           PIC X.
          05 LI-VEHICLE-VALUE           PIC 9(09)V99.
          05 LI-AGE-KNOWN               PIC X.
          05 LI-VEHICLE-AGE             PIC 9(02).

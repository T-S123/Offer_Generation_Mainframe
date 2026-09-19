      * Reads a fixed-width offer-variant request and returns the result
      * from the LIOF01C policy.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LIOBAT01.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY LIOFREQ.
       01 LI-RESULT                     PIC X(32).
       PROCEDURE DIVISION.
      * Reads one offer-variant record, evaluates it with LIOF01C and
      * displays the resulting reason code.
       0000-MAIN.
           ACCEPT LI-OFFER-REQUEST
           CALL 'LIOF01C' USING LI-OFFER-REQUEST LI-RESULT
           DISPLAY LI-RESULT
           STOP RUN.

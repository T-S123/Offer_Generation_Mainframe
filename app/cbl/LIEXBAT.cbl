      * Exposes the campaign execution policy through fixed-width
      * standard input and output records.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LIEXBAT.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY LIEXREQ.
       01 LI-RESULT                     PIC X(32).
       PROCEDURE DIVISION.
      * Reads one execution request, calls LIEX01C and displays its
      * policy result.
       0000-MAIN.
           ACCEPT LI-EXECUTION-REQUEST
           CALL 'LIEX01C' USING LI-EXECUTION-REQUEST LI-RESULT
           DISPLAY LI-RESULT
           STOP RUN.

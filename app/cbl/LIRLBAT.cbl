      * Streams repeated Business rule requests through LIRL01C for
      * local API and batch callers.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LIRLBAT.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY LIRLREQ.
       01 RL-RESPONSE.
          05 RL-STATUS                 PIC X.
          05 RL-REASONS                PIC X(19).
       PROCEDURE DIVISION.
      * Reads each fixed-width rule request, calls LIRL01C and displays
      * its status and reason flags.
       0000-MAIN.
           PERFORM FOREVER
               MOVE SPACES TO RL-REQUEST
               ACCEPT RL-REQUEST
               IF RL-REQUEST = SPACES STOP RUN END-IF
               CALL 'LIRL01C' USING RL-REQUEST RL-RESPONSE
               DISPLAY RL-RESPONSE
           END-PERFORM.

      * Keeps a COBOL credit-policy process available for repeated
      * fixed-width requests on standard input and output.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LICBAT01.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-LINE                      PIC X(76).
       01 WS-END                       PIC 9 VALUE 0.
       COPY LICBREQ.
       COPY LICBRES.
       PROCEDURE DIVISION.
      * Processes repeated standard-input credit requests until the
      * termination marker is received.
       0000-PROCESS-STREAM.
           PERFORM UNTIL WS-END = 1
               MOVE SPACES TO WS-LINE
               ACCEPT WS-LINE
               IF WS-LINE(1:3) = 'END' OR WS-LINE = SPACES
                   GOBACK
               END-IF
               MOVE WS-LINE TO LI-CB-REQUEST
               CALL 'LICB01C' USING LI-CB-REQUEST LI-CB-RESPONSE
               DISPLAY LI-CB-RESPONSE
           END-PERFORM
           GOBACK.

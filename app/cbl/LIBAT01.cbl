      * Streams fixed-width underwriting requests through LIUW01C using
      * the configured sequential input and output files.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LIBAT01.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT REQUEST-FILE ASSIGN TO LIREQ
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS WS-INPUT-STATUS.
           SELECT RESPONSE-FILE ASSIGN TO LIRSP
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS WS-OUTPUT-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD REQUEST-FILE.
       01 INPUT-RECORD                  PIC X(74).
       FD RESPONSE-FILE.
       01 OUTPUT-RECORD                 PIC X(33).
       WORKING-STORAGE SECTION.
       01 WS-INPUT-STATUS               PIC XX.
       01 WS-OUTPUT-STATUS              PIC XX.
       01 WS-END                       PIC 9 VALUE 0.
       COPY LIUWREQ.
       COPY LIUWRES.
       PROCEDURE DIVISION.
      * Reads each underwriting input record, invokes LIUW01C and writes
      * its response until end of file.
       0000-PROCESS-BATCH.
           OPEN INPUT REQUEST-FILE
           OPEN OUTPUT RESPONSE-FILE
           IF WS-INPUT-STATUS NOT = '00' OR
              WS-OUTPUT-STATUS NOT = '00'
               MOVE 12 TO RETURN-CODE
               GOBACK
           END-IF
           PERFORM UNTIL WS-END = 1
               READ REQUEST-FILE INTO LI-UW-REQUEST
                 AT END MOVE 1 TO WS-END
                 NOT AT END
                   IF WS-INPUT-STATUS NOT = '00'
                       MOVE 12 TO RETURN-CODE
                       GOBACK
                   END-IF
                   CALL 'LIUW01C' USING
                       LI-UW-REQUEST LI-UW-RESPONSE
                   MOVE LI-UW-RESPONSE TO OUTPUT-RECORD
                   WRITE OUTPUT-RECORD
                   IF WS-OUTPUT-STATUS NOT = '00'
                       MOVE 12 TO RETURN-CODE
                       GOBACK
                   END-IF
               END-READ
           END-PERFORM
           CLOSE REQUEST-FILE RESPONSE-FILE
           MOVE 0 TO RETURN-CODE
           GOBACK.

      * Initializes one marketing allocation context and streams ordered
      * qualification records through LIMK01C.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LIMBAT01.
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
       01 INPUT-RECORD                  PIC X(109).
       FD RESPONSE-FILE.
       01 OUTPUT-RECORD                 PIC X(42).
       WORKING-STORAGE SECTION.
       01 WS-INPUT-STATUS               PIC XX.
       01 WS-OUTPUT-STATUS              PIC XX.
       01 WS-END                       PIC 9 VALUE 0.
       COPY LIMKREQ.
       COPY LIMKRES.
       COPY LIMKCTX.
       PROCEDURE DIVISION.
      * Initializes the run context and evaluates each ordered marketing
      * request while retaining allocation counters.
       0000-PROCESS-BATCH.
           INITIALIZE LI-MK-CONTEXT
           OPEN INPUT REQUEST-FILE
           OPEN OUTPUT RESPONSE-FILE
           IF WS-INPUT-STATUS NOT = '00' OR
              WS-OUTPUT-STATUS NOT = '00'
               MOVE 12 TO RETURN-CODE GOBACK END-IF
           PERFORM UNTIL WS-END = 1
               READ REQUEST-FILE INTO LI-MK-REQUEST
                 AT END MOVE 1 TO WS-END
                 NOT AT END
                   IF WS-INPUT-STATUS NOT = '00' OR
                      MK-CAMPAIGN < 1 OR MK-CAMPAIGN > 20
                       MOVE 12 TO RETURN-CODE GOBACK END-IF
                   CALL 'LIMK01C' USING LI-MK-REQUEST
                       LI-MK-RESPONSE LI-MK-CONTEXT
                   MOVE LI-MK-RESPONSE TO OUTPUT-RECORD
                   WRITE OUTPUT-RECORD
                   IF WS-OUTPUT-STATUS NOT = '00'
                       MOVE 12 TO RETURN-CODE GOBACK END-IF
               END-READ
           END-PERFORM
           CLOSE REQUEST-FILE RESPONSE-FILE
           MOVE 0 TO RETURN-CODE
           GOBACK.

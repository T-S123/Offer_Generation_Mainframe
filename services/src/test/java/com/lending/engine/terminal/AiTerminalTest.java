package com.lending.engine.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lending.engine.infrastructure.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Real s3270 sessions verify full question paging, multiline input, draft retention and same-request retries over HTTP. */
@Timeout(45)
class AiTerminalTest {
    static ObjectNode object(String json)throws Exception{return (ObjectNode)Json.MAPPER.readTree(json);}
    @Test void fullConversationAndPagedRepliesSurvive3270RoundTrips()throws Exception{
        String question="Please choose the bureau stage.\n"+"More detail about the requested simulation and the original constraints. ".repeat(25)+"QUESTION-END";
        var workflow=object("{\"id\":\"workflow\",\"version\":4,\"workflow\":{\"state\":\"NEEDS_INPUT\",\"revision\":4,\"intent\":\"Original bureau request\",\"budget\":{\"maxEvaluations\":20},\"evaluations\":[],\"messages\":[{\"role\":\"analyst\",\"text\":\"Original bureau request\"}]},\"usage\":{\"evaluations\":0}}");
        ((ObjectNode)workflow.path("workflow")).put("clarification",question);
        var submissions=new CopyOnWriteArrayList<JsonNode>();
        var errors=new CopyOnWriteArrayList<String>();
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/api/v1/",exchange->{
            try{
                String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
                JsonNode result;
                if(path.equals("/api/v1/ai/workflows")&&method.equals("GET"))result=Json.MAPPER.readTree("[{\"id\":\"workflow\",\"data\":{\"state\":\"NEEDS_INPUT\",\"intent\":\"Original bureau request\"}}]");
                else if(path.equals("/api/v1/ai/workflows/workflow"))result=workflow;
                else if(path.equals("/api/v1/ai/workflows/workflow/messages")&&method.equals("POST")){
                    submissions.add(Json.MAPPER.readTree(exchange.getRequestBody()));result=workflow;
                }else if(path.equals("/api/v1/business/drafts"))result=Json.MAPPER.readTree("[{\"id\":\"draft\",\"name\":\"Demo draft\",\"version\":5}]");
                else if(path.equals("/api/v1/business/populations"))result=Json.MAPPER.readTree("[{\"id\":\"discovery\",\"count\":100,\"seed\":1},{\"id\":\"validation\",\"count\":100,\"seed\":2}]");
                else if(path.equals("/api/v1/ai/defaults"))result=object("{\"maxCostUsd\":10}");
                else if(path.equals("/api/v1/ai/workflows")&&method.equals("POST")){
                    submissions.add(Json.MAPPER.readTree(exchange.getRequestBody()));result=workflow;
                }else throw new IllegalStateException("Unexpected route "+method+" "+path);
                byte[] bytes=Json.write(result).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception e){errors.add(e.toString());exchange.sendResponseHeaders(500,-1);}
            finally{exchange.close();}
        });
        http.start();
        try(var terminal=new TerminalServer(0,new ApiClient(http.getAddress().getPort(),"test"))){
            terminal.start();
            var process=new ProcessBuilder("s3270","-model","3279-2","127.0.0.1:"+terminal.port()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try(var session=new Session(process)){
                session.cmd("Wait(5,InputField)");
                session.choose("2");session.choose("8");session.choose("2");session.choose("1");
                assertTrue(session.screen().contains("Read FULL AI message"));
                session.key(4);String full=session.screen();
                for(int i=0;i<5&&!full.contains("QUESTION-END");i++){session.key(8);full+=session.screen();}
                assertTrue(full.contains("QUESTION-END"),"The end of a long question must be reachable");
                assertTrue(full.contains("Original bureau request"));
                session.key(3);session.choose("4");
                assertTrue(session.screen().contains("original request is already retained"));
                String first="Explore only bureau minimum score from 680 to 740, step 1.";
                String second="Keep this draft's offer terms, marketing and underwriting fixed.";
                String third="Use the approved demo policy. Report both best-tested winners.";
                session.put(9,6,first);session.put(10,6,second);session.put(11,6,third);
                session.key(8);assertTrue(session.screen().contains("Text page 2/11"));
                session.put(9,6,"Additional constraint on page two.");
                session.key(7);
                assertTrue(session.screen().contains(first));assertTrue(session.screen().contains(second));assertTrue(session.screen().contains(third));
                session.key(4);session.key(3);assertTrue(session.screen().contains(first),"Reading AI must preserve an unsent reply");
                session.enter();
                assertEquals(first+"\n"+second+"\n"+third+"\n\n\n\n\n\nAdditional constraint on page two.",submissions.get(0).path("text").asText());
                assertEquals(4,submissions.get(0).path("expectedVersion").asInt());
                session.choose("R");
                assertTrue(submissions.get(1).path("text").asText().contains("Preserve the original scope"));

                // The same editor must work for initial requests, not just replies.
                session.key(3);session.choose("1");session.choose("1");session.choose("1");session.choose("2");
                session.put(9,6,first);session.put(10,6,second);session.put(11,6,third);
                Path shots=Path.of("target/presentation");Files.createDirectories(shots);
                Files.writeString(shots.resolve("ai-multiline-editor.txt"),session.screen());
                session.enter();assertTrue(session.screen().contains("Simulation attempt limit"));
                session.key(3);assertTrue(session.screen().contains(first),"Back from budget must preserve the request");
                session.enter();session.enter();
                assertEquals(first+"\n"+second+"\n"+third,submissions.get(2).path("intent").asText());
                assertEquals("draft",submissions.get(2).path("draftId").asText());
                assertTrue(errors.isEmpty(),errors.toString());
            }
        }finally{http.stop(0);}
    }
    /** Drives the real 3270 client without accessing production services or data. */
    static final class Session implements AutoCloseable{
        final Process process;final PrintWriter out;final BufferedReader in;
        Session(Process process){this.process=process;out=new PrintWriter(process.getOutputStream(),true);in=new BufferedReader(new InputStreamReader(process.getInputStream()));}
        String cmd(String command)throws Exception{out.println(command);StringBuilder text=new StringBuilder();for(String line;(line=in.readLine())!=null;){if(line.equals("ok"))return text.toString();if(line.equals("error"))throw new AssertionError(command+": "+text);text.append(line).append('\n');}throw new AssertionError("Terminal closed");}
        String screen()throws Exception{return cmd("Ascii()");}
        void enter()throws Exception{cmd("Enter()");cmd("Wait(5,Unlock)");}
        void key(int number)throws Exception{cmd("PF("+number+")");cmd("Wait(5,Unlock)");}
        void choose(String option)throws Exception{cmd("String(\""+option+"\")");enter();}
        void put(int row,int column,String text)throws Exception{cmd("MoveCursor("+row+","+column+")");cmd("EraseEOF()");cmd("String(\""+text.replace("\\","\\\\").replace("\"","\\\"")+"\")");}
        public void close(){process.destroyForcibly();out.close();try{in.close();}catch(IOException ignored){}}
    }
}

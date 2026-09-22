/** Verifies that disconnected HTTP clients do not turn completed responses into application failures or trigger a second response. */
package com.lending.engine;

import com.lending.engine.api.ApiServer;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises response transport errors separately from request-processing errors without modifying application data. */
class ApiResponseTest {
    /** Checks both header and body disconnections, including closure and query-string redaction. */
    @Test void disconnectedHealthProbeNeverAttemptsAnUnavailableResponse() throws Exception {
        for(boolean headers : new boolean[]{true,false}) {
            var exchange=new Exchange("GET","/api/v1/health?private=must-not-log");
            exchange.failHeaders=headers;exchange.failBody=!headers;
            String log=handle(exchange);
            assertEquals(1,exchange.responses);
            assertEquals(200,exchange.status);
            assertTrue(exchange.closed);
            assertTrue(log.contains("API response delivery failed: GET /api/v1/health (IOException)"),log);
            assertFalse(log.contains("API request failed"),log);
            assertFalse(log.contains("must-not-log"),log);
        }
    }

    /** Ensures a genuine request-body failure still returns unavailable and identifies the failing route. */
    @Test void requestReadFailureRemainsAnApplicationFailure() throws Exception {
        var exchange=new Exchange("POST","/api/v1/customers");exchange.failRead=true;
        String log=handle(exchange);
        assertEquals(1,exchange.responses);
        assertEquals(503,exchange.status);
        assertTrue(exchange.output.toString(StandardCharsets.UTF_8).contains("no success is implied"));
        assertTrue(log.contains("API request failed: POST /api/v1/customers (EOFException)"),log);
        assertTrue(exchange.closed);
    }

    /** Preserves normal health responses and authentication failures after transport handling changes. */
    @Test void successfulHealthAndUnauthorizedRequestsKeepTheirStatuses() throws Exception {
        var health=new Exchange("GET","/api/v1/health");assertEquals("",handle(health));
        assertEquals(200,health.status);assertTrue(health.output.toString(StandardCharsets.UTF_8).contains("UP"));
        var denied=new Exchange("GET","/api/v1/customers");denied.request.remove("Authorization");
        assertEquals("",handle(denied));assertEquals(401,denied.status);assertEquals(1,denied.responses);
    }

    /** Invokes the registered handler with a controlled exchange and captures its sanitized diagnostic output. */
    private String handle(Exchange exchange) throws Exception {
        var output=new ByteArrayOutputStream();var previous=System.err;
        try(var server=new ApiServer(null,0,"test-token");var capture=new PrintStream(output,true,StandardCharsets.UTF_8)) {
            System.setErr(capture);
            var handler=ApiServer.class.getDeclaredMethod("handle",HttpExchange.class);handler.setAccessible(true);
            handler.invoke(server,exchange);
        } finally {System.setErr(previous);}
        return output.toString(StandardCharsets.UTF_8);
    }

    /** Models a connection that can disconnect before headers, during body delivery or while receiving input. */
    private static final class Exchange extends HttpExchange {
        final Headers request=new Headers(),response=new Headers();
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final String method;final URI uri;
        boolean failHeaders,failBody,failRead,closed;int responses,status=-1;
        /** Prepares a local JSON request with a test-only bearer credential. */
        Exchange(String method,String path){this.method=method;uri=URI.create(path);request.set("Authorization","Bearer test-token");request.set("Content-Type","application/json");}
        /** Returns the request headers supplied by the fixture. */
        public Headers getRequestHeaders(){return request;}
        /** Returns the headers populated by the API. */
        public Headers getResponseHeaders(){return response;}
        /** Returns the requested path and optional query. */
        public URI getRequestURI(){return uri;}
        /** Returns the requested HTTP verb. */
        public String getRequestMethod(){return method;}
        /** Indicates that this fixture has no live server context. */
        public HttpContext getHttpContext(){return null;}
        /** Records connection closure for cleanup assertions. */
        public void close(){closed=true;}
        /** Supplies an empty request stream or a failed client upload. */
        public InputStream getRequestBody(){return new InputStream(){
            /** Simulates a failed upload when requested by the test. */
            public int read() throws IOException {if(failRead)throw new EOFException("fixture upload failure");return -1;}
        };}
        /** Supplies the response sink or a disconnected client. */
        public OutputStream getResponseBody(){return new OutputStream(){
            /** Writes a byte unless the fixture represents a disconnected receiver. */
            public void write(int b) throws IOException {if(failBody)throw new IOException("fixture disconnect");output.write(b);}
        };}
        /** Counts header attempts and optionally simulates a disconnect before they are transmitted. */
        public void sendResponseHeaders(int code,long length) throws IOException {responses++;status=code;if(failHeaders)throw new IOException("fixture disconnect");}
        /** Returns the local address used for this synthetic peer. */
        public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1);}
        /** Returns the last attempted response status. */
        public int getResponseCode(){return status;}
        /** Returns the local address used for this synthetic listener. */
        public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",2);}
        /** Returns the fixture's HTTP protocol. */
        public String getProtocol(){return "HTTP/1.1";}
        /** Indicates that the fixture has no exchange attributes. */
        public Object getAttribute(String name){return null;}
        /** Ignores unused exchange attributes in this fixture. */
        public void setAttribute(String name,Object value){}
        /** Leaves the controlled streams in place. */
        public void setStreams(InputStream input,OutputStream output){}
        /** Indicates that authentication is handled by the API's bearer-token check. */
        public HttpPrincipal getPrincipal(){return null;}
    }
}

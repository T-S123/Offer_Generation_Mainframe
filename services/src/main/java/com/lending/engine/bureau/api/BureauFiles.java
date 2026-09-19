/**
 * Streaming NDJSON client: stable request IDs make batch manifests replayable; imports use optimistic
 * profile versions.
 */
package com.lending.engine.bureau.api;

import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.infrastructure.Json;
import com.lending.engine.terminal.ApiClient;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Streaming NDJSON client: stable request IDs make batch manifests replayable; imports use optimistic
 * profile versions.
 */
public final class BureauFiles {
    /** Prevents instantiation of this utility-only type. */
    private BureauFiles() {}
    /** Carries profile import data for bureau files operations. */
    public record ProfileImport(String customerId,Integer expectedVersion,Facts facts) {}
    /** Streams a bounded NDJSON manifest through the public batch or bureau-profile API. */
    public static void run(ApiClient api,String mode,Path file)throws IOException {
        int line=0;try(var input=Files.newBufferedReader(file)){
            String record;while((record=boundedLine(input))!=null){line++;if(record.isBlank())continue;
                try{
                    if(mode.equals("--bureau-batch")){var request=Json.read(record,BatchRequest.class);var result=api.call("POST","bureau/batches",request);System.out.println(Json.write(Map.of("line",line,"requestId",request.requestId(),"status",result.path("status").asText())));}
                    else{var request=Json.read(record,ProfileImport.class);var result=api.call("PUT","bureau/profiles/"+request.customerId(),new ProfileChange(request.expectedVersion(),"LOCAL_IMPORT",request.facts()));System.out.println(Json.write(Map.of("line",line,"customerId",request.customerId(),"version",result.path("version").asInt())));}
                }catch(RuntimeException e){throw new IOException("Manifest stopped at line "+line+". Earlier lines remain committed. "+e.getMessage(),e);}
            }
        }
    }
    /** Reads one manifest line while enforcing the input-size limit. */
    private static String boundedLine(Reader reader)throws IOException{var line=new StringBuilder();int ch;while((ch=reader.read())!=-1){if(ch=='\n')return line.toString();if(line.length()==1024*1024)throw new IOException("Manifest line exceeds 1 MiB");if(ch!='\r')line.append((char)ch);}return line.isEmpty()?null:line.toString();}
}

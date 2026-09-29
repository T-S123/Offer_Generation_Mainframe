package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.lending.ai.Json.*;

/** Content-addressed policy ingestion with isolated parsing, quality flags and approval tied to exact bytes. */
public final class PolicyIndex {
    private static final Set<String> EXTENSIONS=Set.of("pdf","docx","doc","txt","md","html","htm","rtf","odt");
    private final Path root;private final Store store;private final boolean ocr;
    public PolicyIndex(Path root,Store store,boolean ocr){this.root=root.toAbsolutePath().normalize();this.store=store;this.ocr=ocr;}
    /** Reindexes changed files, retires removed content and never follows links outside the configured folder. */
    public synchronized JsonNode scan() {
        try {
            Files.createDirectories(root);Path realRoot=root.toRealPath();
            var seen=new HashSet<String>();int changed=0;
            try(var walk=Files.walk(root,5)){
                for(Path p:walk.filter(f->Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)).filter(f->EXTENSIONS.contains(f.getFileName().toString().substring(f.getFileName().toString().lastIndexOf('.')+1).toLowerCase(Locale.ROOT))).limit(201).toList()){
                    String name=root.relativize(p).toString().replace('\\','/');String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
                    if(!EXTENSIONS.contains(ext)||name.equals("README.md"))continue;
                    require(seen.size()<200,"DOCUMENT_LIMIT","At most 200 policy documents are indexed");
                    if(!p.toRealPath().startsWith(realRoot))continue;
                    String id=digest(name.getBytes(StandardCharsets.UTF_8));seen.add(id);
                    var old=store.find("DOCUMENT",id);long size=Files.size(p);
                    byte[] bytes=size<=20*1024*1024?Files.readAllBytes(p):null;
                    String hash=bytes==null?"OVERSIZED":digest(bytes);
                    if(old.isPresent()&&old.get().data().path("hash").asText().equals(hash)&&!old.get().data().path("removed").asBoolean())continue;
                    var extraction=size>20*1024*1024?obj("status","TOO_LARGE","text","","mediaType","unknown","pages",0):extractSnapshot(bytes,ext,ocr);
                    var doc=obj("id",id,"name",name,"hash",hash,"size",size,"mediaType",extraction.path("mediaType"),"pages",extraction.path("pages"),
                        "extractionStatus",extraction.path("status"),"characterCount",extraction.path("text").asText().length(),
                        "textArtifact",store.artifact(obj("text",extraction.path("text"))),"approved",false,"removed",false,"indexedAt",Instant.now().toString());
                    if(old.isEmpty())store.create("DOCUMENT",id,doc);else store.update("DOCUMENT",id,old.get().version(),doc);changed++;
                }
            }
            for(var record:allDocuments())if(!seen.contains(record.id())&&!record.data().path("removed").asBoolean()){
                var n=record.data().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)n).put("removed",true).put("approved",false);
                store.update("DOCUMENT",record.id(),record.version(),n);
            }
            return obj("scanned",seen.size(),"changed",changed,"documents",documents());
        }catch(IOException e){throw new Fault(503,"POLICY_IO","Policy folder could not be scanned");}
    }
    private List<Store.Saved> allDocuments(){var out=new ArrayList<Store.Saved>();String after="";for(;;){var page=store.list("DOCUMENT",after,100);out.addAll(page);if(page.size()<100)break;after=page.get(page.size()-1).id();}return out;}
    public List<JsonNode> documents(){return allDocuments().stream().map(Store.Saved::data).toList();}
    /** Explicit analyst approval covers the current content hash and applicability; a file edit revokes it. */
    public JsonNode approve(String id,String expectedHash,String product,String geography,String expiresOn,String analyst) {
        require(Set.of("PERSONAL_LOAN","CREDIT_CARD","AUTO_LOAN","ALL").contains(product),"INVALID_PRODUCT","Choose a supported product");
        require(geography.equals("US"),"INVALID_GEOGRAPHY","This engine supports US products");
        require(!LocalDate.parse(expiresOn).isBefore(LocalDate.now(ZoneOffset.UTC)),"EXPIRED_POLICY","Policy approval is expired");
        var saved=store.get("DOCUMENT",id);var n=(com.fasterxml.jackson.databind.node.ObjectNode)saved.data().deepCopy();
        require(!n.path("removed").asBoolean()&&n.path("hash").asText().equals(expectedHash),"STALE_POLICY","Document changed; review the current extraction");
        require(n.path("extractionStatus").asText().equals("READY"),"EXTRACTION_REVIEW","Only complete readable extractions can be approved");
        n.put("approved",true).put("product",product).put("geography",geography).put("expiresOn",expiresOn).put("approvedBy",analyst).put("approvedAt",Instant.now().toString());
        return store.update("DOCUMENT",id,saved.version(),n).data();
    }
    /** Revalidates citation identities against current bytes, approval and applicability before execution or publication. */
    public synchronized void validateEvidence(String product,List<String> evidenceIds){
        scan();require(!evidenceIds.isEmpty(),"EVIDENCE_REQUIRED","Approved evidence required");
        for(String evidence:evidenceIds){
            String[] parts=evidence.split(":");require(parts.length==2&&parts[1].matches("[0-9]+"),"STALE_POLICY","Invalid citation identity");
            boolean valid=false;
            for(var item:allDocuments()){
                var d=item.data();if(d.path("hash").asText().equals(parts[0])&&d.path("approved").asBoolean()&&!d.path("removed").asBoolean()
                    &&Set.of("ALL",product).contains(d.path("product").asText())&&!LocalDate.parse(d.path("expiresOn").asText()).isBefore(LocalDate.now(ZoneOffset.UTC))
                    &&Long.parseLong(parts[1])<d.path("characterCount").asLong())valid=true;
            }
            require(valid,"STALE_POLICY","Referenced policy changed, expired or was withdrawn; create a freshly researched plan");
        }
    }
    /** Searches only approved, current content and returns bounded excerpts with stable citation identities. */
    public List<JsonNode> search(String query,String product,int limit){
        require(query!=null&&query.length()<=1000&&limit>=1&&limit<=12,"SEARCH_LIMIT","Invalid policy search");
        var terms=Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).filter(t->t.length()>2).distinct().toList();
        var hits=new ArrayList<JsonNode>();
        for(var saved:allDocuments()){
            var d=saved.data();if(!d.path("approved").asBoolean()||d.path("removed").asBoolean()||!Set.of("ALL",product).contains(d.path("product").asText())||LocalDate.parse(d.path("expiresOn").asText()).isBefore(LocalDate.now(ZoneOffset.UTC)))continue;
            String text=store.artifact(d.path("textArtifact").asText()).path("text").asText();
            for(int start=0;start<text.length();start+=1000){
                String chunk=text.substring(start,Math.min(text.length(),start+1400));long score=terms.stream().filter(t->chunk.toLowerCase(Locale.ROOT).contains(t)).count();
                if(score==0)continue;
                String citation=d.path("hash").asText()+":"+start;
                hits.add(obj("evidenceId",citation,"documentId",saved.id(),"sourceClass","APPROVED_INTERNAL_POLICY","source",d.path("name"),
                    "contentHash",d.path("hash"),"offset",start,"quote",chunk,"score",score,"product",d.path("product"),"expiresOn",d.path("expiresOn")));
            }
        }
        return hits.stream().sorted(Comparator.comparingLong((JsonNode n)->n.path("score").asLong()).reversed().thenComparing(n->n.path("evidenceId").asText())).limit(limit).toList();
    }
    /** Parses the exact bytes whose hash is approved, even when the original file changes during extraction. */
    private static JsonNode extractSnapshot(byte[] bytes,String extension,boolean ocr)throws IOException{
        Path snapshot=Files.createTempFile("policy-input-","."+extension);
        try{Files.write(snapshot,bytes);return extract(snapshot,ocr);}finally{Files.deleteIfExists(snapshot);}
    }
    /** Runs parsers in a disposable process with memory, time, input and output bounds. */
    static JsonNode extract(Path input,boolean ocr) {
        Path output=null;Process process=null;
        try{
            output=Files.createTempFile("policy-extract-",".json");
            var args=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-cp",System.getProperty("java.class.path"),PolicyExtractor.class.getName(),input.toAbsolutePath().toString(),output.toString(),Boolean.toString(ocr)));
            process=new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);return obj("status","EXTRACTION_TIMEOUT","text","","mediaType","unknown","pages",0);}
            if(process.exitValue()!=0||Files.size(output)>5000000)return obj("status","EXTRACTION_FAILED","text","","mediaType","unknown","pages",0);
            return read(Files.readString(output));
        }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return obj("status","EXTRACTION_FAILED","text","","mediaType","unknown","pages",0);}
        finally{if(process!=null&&process.isAlive())process.destroyForcibly();if(output!=null)try{Files.deleteIfExists(output);}catch(IOException ignored){}}
    }
}

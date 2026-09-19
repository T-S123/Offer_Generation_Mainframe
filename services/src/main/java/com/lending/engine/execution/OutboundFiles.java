/**
 * Local CSV and CP037 fixed-block files with verifiable recovery; an atomic manifest marks publication and
 * withdrawal removes active data files.
 */
package com.lending.engine.execution;
import com.lending.engine.execution.Execution.Package;
import com.lending.engine.infrastructure.Json;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.hash;
import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;
import java.io.*;

/**
 * Local CSV and CP037 fixed-block files with verifiable recovery; an atomic manifest marks publication and
 * withdrawal removes active data files.
 */
public final class OutboundFiles {
    public static final int[] WIDTHS={63,10,36,80,80,10,80,12,1,6,80,16,100,14,8,14,3,30};
    public static final int RECORD_LENGTH=Arrays.stream(WIDTHS).sum();private final Path root;
    /** Initializes outbound files with the supplied configuration and dependencies. */
    public OutboundFiles(Path root){this.root=root.toAbsolutePath().normalize();}
    /** Checks that the manifest and data files match the expected active package revision. */
    public boolean ready(Package p){try{var dir=directory(p.id());var manifest=Json.MAPPER.readTree(Files.readString(dir.resolve("manifest.json")));if(manifest.path("version").asLong()!=p.version()||!manifest.path("status").asText().equals(p.status()))return false;if(p.status().equals("ACTIVE")){download(p,"csv");download(p,"dat");return true;}return !Files.exists(dir.resolve("offers.csv"))&&!Files.exists(dir.resolve("offers.dat"));}catch(Exception e){return false;}}
    /** Resolves the local output directory for a campaign package. */
    public Path directory(String id){if(!id.matches("PK-[a-f0-9]{60}"))throw new IllegalArgumentException("Invalid package ID");return root.resolve(id);}
    /** Writes an output file through a temporary path and atomically replaces its published destination. */
    private void atomic(Path target,byte[] bytes)throws IOException {Files.createDirectories(target.getParent());var temp=Files.createTempFile(target.getParent(),"pending-",".tmp");try{Files.write(temp,bytes);Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
    /** Writes CSV and IBM037 data files and publishes their checksums through an atomic manifest. */
    public void write(Package p)throws IOException {Path directory=directory(p.id());Files.createDirectories(directory);var manifest=new LinkedHashMap<String,Object>();manifest.put("packageId",p.id());manifest.put("version",p.version());manifest.put("status",p.status());manifest.put("synthetic",true);manifest.put("validUntil",p.validUntil());manifest.put("recordLength",RECORD_LENGTH);manifest.put("encoding","IBM037");manifest.put("reason",p.reason());
        if(!p.status().equals("ACTIVE")){atomic(directory.resolve("manifest.json"),Json.write(manifest).getBytes(StandardCharsets.UTF_8));Files.deleteIfExists(directory.resolve("offers.csv"));Files.deleteIfExists(directory.resolve("offers.dat"));return;}
        StringBuilder csv=new StringBuilder("package_id,package_version,customer_id,campaign_id,response_id,response_version,catalog_offer_id,kind,lead,channel,synthetic_email,synthetic_phone,synthetic_postal_address,amount_usd,apr_pct,annual_fee_usd,term_months,valid_until\r\n");var fixed=new ByteArrayOutputStream();var charset=Charset.forName("IBM037");
        for(var a:p.alternatives()){var t=a.terms();String[] values={p.id(),Long.toString(p.version()),p.customerId(),p.campaignId(),a.responseId(),Long.toString(a.responseVersion()),a.catalogOfferId(),a.kind(),a.responseId().equals(p.leadResponseId())&&a.kind().equals(p.leadKind())?"Y":"N",p.channel(),p.contact().email(),p.contact().phone(),p.contact().postalAddress(),t.amountUsd().toPlainString(),t.aprPct().toPlainString(),t.annualFeeUsd().toPlainString(),Integer.toString(t.termMonths()),a.validUntil()};var row=new StringBuilder();for(int i=0;i<values.length;i++){String value=values[i];if(value.length()>WIDTHS[i]||!charset.newEncoder().canEncode(value)||value.chars().anyMatch(Character::isISOControl))throw new IOException("Outbound field exceeds its copybook");if(i>0)csv.append(',');csv.append('"').append(value.replace("\"","\"\"")).append('"');row.append(value).append(" ".repeat(WIDTHS[i]-value.length()));}csv.append("\r\n");fixed.writeBytes(row.toString().getBytes(charset));}
        byte[] csvBytes=csv.toString().getBytes(StandardCharsets.UTF_8),fixedBytes=fixed.toByteArray();manifest.put("records",p.alternatives().size());manifest.put("csvSha256",digest(csvBytes));manifest.put("datSha256",digest(fixedBytes));

        atomic(directory.resolve("manifest.json"),Json.write(Map.of("packageId",p.id(),"version",p.version(),"status","WRITING")).getBytes(StandardCharsets.UTF_8));atomic(directory.resolve("offers.csv"),csvBytes);atomic(directory.resolve("offers.dat"),fixedBytes);atomic(directory.resolve("manifest.json"),Json.write(manifest).getBytes(StandardCharsets.UTF_8));
    }
    /** Reads an active file bundle and verifies its revision and checksum before returning encoded content. */
    public Map<String,Object> download(Package p,String format)throws IOException {String file=switch(format){case "csv"->"offers.csv";case "dat"->"offers.dat";default->throw new IllegalArgumentException("Use csv or dat");};Path directory=directory(p.id());var manifest=Json.MAPPER.readTree(Files.readString(directory.resolve("manifest.json")));if(!manifest.path("status").asText().equals("ACTIVE")||manifest.path("version").asLong()!=p.version())throw new IOException("Export revision is not ready");byte[] bytes=Files.readAllBytes(directory.resolve(file));String digest=digest(bytes);if(!manifest.path(format+"Sha256").asText().equals(digest))throw new IOException("Export checksum mismatch");return Map.of("packageId",p.id(),"version",p.version(),"filename",file,"encoding",format.equals("csv")?"UTF-8":"IBM037","sha256",digest,"contentBase64",Base64.getEncoder().encodeToString(bytes));}
    /** Computes the SHA-256 checksum used to verify exported file contents. */
    public static String digest(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}

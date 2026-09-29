package com.lending.ai;

import org.apache.tika.parser.*;
import org.apache.tika.metadata.*;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import java.nio.file.*;
import static com.lending.ai.Json.*;

/** Disposable Apache Tika worker: detects actual format, extracts text and reports incomplete or unreadable documents. */
public final class PolicyExtractor {
    private PolicyExtractor(){}
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]),output=Path.of(args[1]);boolean ocr=Boolean.parseBoolean(args[2]);
        var metadata=new Metadata();metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY,input.getFileName().toString());
        var context=new ParseContext();var pdf=new PDFParserConfig();pdf.setSortByPosition(true);
        pdf.setOcrStrategy(ocr?PDFParserConfig.OCR_STRATEGY.AUTO:PDFParserConfig.OCR_STRATEGY.NO_OCR);context.set(PDFParserConfig.class,pdf);
        var tess=new TesseractOCRConfig();tess.setSkipOcr(!ocr);context.set(TesseractOCRConfig.class,tess);
        context.set(EmbeddedDocumentExtractor.class,new EmbeddedDocumentExtractor(){
            public boolean shouldParseEmbedded(Metadata m){return false;}
            public void parseEmbedded(java.io.InputStream s,org.xml.sax.ContentHandler h,Metadata m,boolean html){}
        });
        var handler=new BodyContentHandler(1000000);String status="READY";
        try(var stream=Files.newInputStream(input)){new AutoDetectParser().parse(stream,handler,metadata,context);}
        catch(Exception e){status="INCOMPLETE_EXTRACTION";}
        String text=handler.toString().replace("\u0000","").replaceAll("[\\t ]+"," ").replaceAll("\\n{4,}","\n\n").strip();
        long letters=text.codePoints().filter(Character::isLetterOrDigit).count();
        if(text.length()<40||letters<20)status=ocr?"UNREADABLE_OR_EMPTY":"OCR_OR_REVIEW_REQUIRED";
        if(text.length()>100&&text.chars().filter(c->c==0xfffd).count()>text.length()/100)status="ENCODING_REVIEW";
        Files.writeString(output,write(obj("status",status,"text",text,"mediaType",metadata.get(Metadata.CONTENT_TYPE),"pages",metadata.get("xmpTPg:NPages"))));
    }
}

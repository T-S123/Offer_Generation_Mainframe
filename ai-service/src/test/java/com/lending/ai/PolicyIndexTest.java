package com.lending.ai;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.lending.ai.Json.*;

/** Exercises genuine text, PDF and DOCX extraction and exact-version approval, including unreadable documents. */
class PolicyIndexTest {
    @TempDir Path dir;
    @Test void textApprovalIsRevokedOnEditAndRemoval()throws Exception{
        try(var store=new Store("jdbc:h2:mem:"+UUID.randomUUID()+";DATABASE_TO_LOWER=TRUE","sa","","research")){
            var index=new PolicyIndex(dir,store,false);var file=dir.resolve("credit.md");
            Files.writeString(file,"Credit score policy for personal loans. Research is permitted to test bureau minimum credit scores between 680 and 740, subject to underwriting constraints.");
            index.scan();var doc=index.documents().get(0);assertEquals("READY",doc.path("extractionStatus").asText());assertTrue(index.search("credit score","PERSONAL_LOAN",5).isEmpty());
            index.approve(doc.path("id").asText(),doc.path("hash").asText(),"PERSONAL_LOAN","US",LocalDate.now().plusDays(30).toString(),"analyst");
            assertFalse(index.search("credit score","PERSONAL_LOAN",5).isEmpty());assertTrue(index.search("credit score","AUTO_LOAN",5).isEmpty());
            Files.writeString(file,"Updated credit score policy. Bureau minimum scores must now be between 700 and 760. Review the updated version before use.");index.scan();assertTrue(index.search("credit","PERSONAL_LOAN",5).isEmpty());
            Files.delete(file);index.scan();assertTrue(index.documents().get(0).path("removed").asBoolean());
        }
    }
    @Test void extractsPdfAndWordByContent()throws Exception{
        Path pdf=dir.resolve("policy.pdf");
        try(var doc=new org.apache.pdfbox.pdmodel.PDDocument()){
            var page=new org.apache.pdfbox.pdmodel.PDPage();doc.addPage(page);
            try(var stream=new org.apache.pdfbox.pdmodel.PDPageContentStream(doc,page)){stream.beginText();stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(40,700);stream.showText("Policy evidence: approved bureau credit score range 680 to 740 for personal loans.");stream.endText();}
            doc.save(pdf.toFile());
        }
        var result=PolicyIndex.extract(pdf,false);assertEquals("READY",result.path("status").asText());assertTrue(result.path("text").asText().contains("680"));assertTrue(result.path("mediaType").asText().contains("pdf"));
        Path word=dir.resolve("policy.docx");
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument()){doc.createParagraph().createRun().setText("Approved policy: personal loan bureau score exploration spans 680 to 740 with unchanged underwriting.");try(var out=Files.newOutputStream(word)){doc.write(out);}}
        result=PolicyIndex.extract(word,false);assertEquals("READY",result.path("status").asText());assertTrue(result.path("text").asText().contains("underwriting"));
        Path empty=dir.resolve("empty.pdf");try(var doc=new org.apache.pdfbox.pdmodel.PDDocument()){doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());doc.save(empty.toFile());}
        assertEquals("OCR_OR_REVIEW_REQUIRED",PolicyIndex.extract(empty,false).path("status").asText());
    }
}

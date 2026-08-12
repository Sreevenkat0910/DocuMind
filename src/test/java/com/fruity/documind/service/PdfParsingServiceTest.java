package com.fruity.documind.service;

import com.fruity.documind.service.PdfParsingService.ParsedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit test (no Spring context): builds a 2-page PDF in memory, then confirms the
 * parser recovers the right page count and the right text on each page — i.e. that page
 * boundaries are preserved.
 */
class PdfParsingServiceTest {

    private final PdfParsingService service = new PdfParsingService();

    @Test
    void extractsTextPerPageWithPageNumbers() throws Exception {
        byte[] pdf = twoPagePdf("Hello from page one", "Second page content here");

        ParsedDocument result = service.parse(pdf);

        assertEquals(2, result.pageCount(), "should detect both pages");
        assertEquals(2, result.pages().size());

        assertEquals(1, result.pages().get(0).pageNumber());
        assertTrue(result.pages().get(0).text().contains("Hello from page one"),
                "page 1 should contain its own text");
        assertFalse(result.pages().get(0).text().contains("Second page content"),
                "page 1 must NOT leak page 2's text");

        assertEquals(2, result.pages().get(1).pageNumber());
        assertTrue(result.pages().get(1).text().contains("Second page content here"),
                "page 2 should contain its own text");
        assertFalse(result.pages().get(1).text().contains("Hello from page one"),
                "page 2 must NOT leak page 1's text");

        // fullText stitches both pages together in order.
        String full = result.fullText();
        assertTrue(full.indexOf("Hello from page one") < full.indexOf("Second page content here"),
                "fullText should preserve page order");
    }

    private byte[] twoPagePdf(String page1Text, String page2Text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addPage(doc, page1Text);
            addPage(doc, page2Text);
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void addPage(PDDocument doc, String text) throws Exception {
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(72, 700);
            cs.showText(text);
            cs.endText();
        }
    }
}

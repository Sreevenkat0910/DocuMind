package com.fruity.documind.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 parsing (README §7). Extracts text from a PDF <b>page by page</b>, so the
 * page a piece of text came from is preserved. That page number flows downstream into
 * {@code DocumentChunk.pageNumber} and, ultimately, {@code Citation.pageNumber} — which
 * is what lets an answer cite "see page 7" back to the source.
 *
 * <p>Scanned/image-only PDFs yield empty text per page here; making those searchable is
 * the job of OCR (Tesseract), deferred to Phase 7. This service does not OCR.
 *
 * <p>Multi-format support (DOCX/XLSX/PPTX via Apache POI) is also Phase 7; for now this
 * is deliberately PDF-only.
 */
@Service
public class PdfParsingService {

    /** Text extracted from a single 1-based page. */
    public record ParsedPage(int pageNumber, String text) {}

    /** The full result of parsing one PDF. */
    public record ParsedDocument(int pageCount, List<ParsedPage> pages) {

        /** Convenience: all page text joined with blank lines, in page order. */
        public String fullText() {
            StringBuilder sb = new StringBuilder();
            for (ParsedPage page : pages) {
                if (!page.text().isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
                    sb.append(page.text().strip());
                }
            }
            return sb.toString();
        }
    }

    /** Parse a PDF stored on disk. */
    public ParsedDocument parse(Path path) throws IOException {
        return parse(path.toFile());
    }

    /** Parse a PDF from an in-memory byte array (e.g. a fresh multipart upload). */
    public ParsedDocument parse(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return extract(document);
        }
    }

    private ParsedDocument parse(File file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            return extract(document);
        }
    }

    private ParsedDocument extract(PDDocument document) throws IOException {
        int pageCount = document.getNumberOfPages();
        List<ParsedPage> pages = new ArrayList<>(pageCount);

        // A single stripper reused across pages; we narrow it to one page at a time so
        // each page's text is isolated (rather than one blob with page separators).
        PDFTextStripper stripper = new PDFTextStripper();
        for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            String text = stripper.getText(document);
            pages.add(new ParsedPage(pageNumber, text == null ? "" : text));
        }

        return new ParsedDocument(pageCount, pages);
    }
}

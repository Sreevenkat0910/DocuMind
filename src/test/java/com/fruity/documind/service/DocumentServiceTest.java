package com.fruity.documind.service;

import com.fruity.documind.entity.Document;
import com.fruity.documind.entity.User;
import com.fruity.documind.enums.DocumentStatus;
import com.fruity.documind.gateway.GatewayClient;
import com.fruity.documind.repository.DocumentPermissionRepository;
import com.fruity.documind.repository.DocumentRepository;
import com.fruity.documind.service.PdfParsingService.ParsedDocument;
import com.fruity.documind.service.PdfParsingService.ParsedPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Verifies the upload orchestration (status lifecycle + failure path) with mocked collaborators. */
class DocumentServiceTest {

    private DocumentRepository documentRepository;
    private DocumentPermissionRepository documentPermissionRepository;
    private FileStorageService fileStorageService;
    private PdfParsingService pdfParsingService;
    private GatewayClient gatewayClient;
    private ChunkIngestionService chunkIngestionService;
    private DocumentService service;
    private User uploader;

    @BeforeEach
    void setup() {
        documentRepository = mock(DocumentRepository.class);
        documentPermissionRepository = mock(DocumentPermissionRepository.class);
        fileStorageService = mock(FileStorageService.class);
        pdfParsingService = mock(PdfParsingService.class);
        gatewayClient = mock(GatewayClient.class);
        chunkIngestionService = mock(ChunkIngestionService.class);
        service = new DocumentService(documentRepository, documentPermissionRepository,
                fileStorageService, pdfParsingService, gatewayClient, chunkIngestionService);
        uploader = new User();

        // save() returns its argument (the same mutated Document instance) back.
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fileStorageService.store(any(), anyString())).thenReturn("/tmp/stored.pdf");
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "report.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
    }

    @Test
    void happyPath_marksIndexed_andIngestsChunks() throws Exception {
        when(pdfParsingService.parse(any(byte[].class)))
                .thenReturn(new ParsedDocument(2, List.of(new ParsedPage(1, "a"), new ParsedPage(2, "b"))));
        when(gatewayClient.chunk(any()))
                .thenReturn(List.of(new GatewayClient.ChunkOutput("a", 1), new GatewayClient.ChunkOutput("b", 2)));

        Document result = service.upload(pdf(), "My Title", uploader);

        assertEquals(DocumentStatus.INDEXED, result.getStatus());
        assertEquals("My Title", result.getTitle());
        assertEquals(2, result.getPageCount());
        verify(chunkIngestionService, times(1)).ingestChunks(any(), any());
        // UPLOADED create + PROCESSING + INDEXED = 3 saves
        verify(documentRepository, times(3)).save(any(Document.class));
        // uploader is granted OWNER access on upload
        verify(documentPermissionRepository, times(1)).save(any());
    }

    @Test
    void scannedPdf_zeroChunks_stillIndexed_butSkipsIngest() throws Exception {
        when(pdfParsingService.parse(any(byte[].class)))
                .thenReturn(new ParsedDocument(1, List.of(new ParsedPage(1, ""))));
        when(gatewayClient.parse(any(byte[].class), anyString())).thenReturn(List.of()); // OCR also finds nothing
        when(gatewayClient.chunk(any())).thenReturn(List.of());

        Document result = service.upload(pdf(), null, uploader);

        assertEquals(DocumentStatus.INDEXED, result.getStatus());
        verify(chunkIngestionService, never()).ingestChunks(any(), any());
    }

    @Test
    void scannedPdf_fallsBackToGatewayOcr_andIndexesTheResult() throws Exception {
        // PDFBox finds no text (scanned/image-only) -> gateway OCR is tried, and DOES find text.
        when(pdfParsingService.parse(any(byte[].class)))
                .thenReturn(new ParsedDocument(1, List.of(new ParsedPage(1, ""))));
        when(gatewayClient.parse(any(byte[].class), eq("report.pdf")))
                .thenReturn(List.of(new GatewayClient.ParsedPage(1, "ocr'd text")));
        when(gatewayClient.chunk(any()))
                .thenReturn(List.of(new GatewayClient.ChunkOutput("ocr'd text", 1)));

        Document result = service.upload(pdf(), null, uploader);

        assertEquals(DocumentStatus.INDEXED, result.getStatus());
        assertEquals(1, result.getPageCount());
        verify(chunkIngestionService, times(1)).ingestChunks(any(), any());
    }

    @Test
    void docxUpload_skipsPdfBox_goesStraightToGatewayParse() throws Exception {
        MockMultipartFile docx = new MockMultipartFile("file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "fake docx".getBytes());
        when(gatewayClient.parse(any(byte[].class), eq("report.docx")))
                .thenReturn(List.of(new GatewayClient.ParsedPage(1, "docx text")));
        when(gatewayClient.chunk(any()))
                .thenReturn(List.of(new GatewayClient.ChunkOutput("docx text", 1)));

        Document result = service.upload(docx, null, uploader);

        assertEquals(DocumentStatus.INDEXED, result.getStatus());
        verify(pdfParsingService, never()).parse(any(byte[].class));
        verify(chunkIngestionService, times(1)).ingestChunks(any(), any());
    }

    @Test
    void processingFailure_marksFailed_andRethrows() throws Exception {
        when(pdfParsingService.parse(any(byte[].class))).thenThrow(new IOException("corrupt pdf"));

        assertThrows(DocumentProcessingException.class, () -> service.upload(pdf(), null, uploader));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeastOnce()).save(captor.capture());
        assertEquals(DocumentStatus.FAILED, captor.getValue().getStatus());
        verify(chunkIngestionService, never()).ingestChunks(any(), any());
    }

    @Test
    void gatewayUnavailable_marksFailed_documentNotSilentlyLost() throws Exception {
        // Plan.md Phase 10 exit criteria: ingestion fails loudly and recoverably (durable FAILED
        // status) rather than silently losing the document when the gateway is down.
        when(pdfParsingService.parse(any(byte[].class)))
                .thenReturn(new ParsedDocument(1, List.of(new ParsedPage(1, "some real extracted text"))));
        when(gatewayClient.chunk(any()))
                .thenThrow(new GatewayClient.GatewayUnavailableException("/chunk", new RuntimeException("connection refused")));

        assertThrows(DocumentProcessingException.class, () -> service.upload(pdf(), null, uploader));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeastOnce()).save(captor.capture());
        assertEquals(DocumentStatus.FAILED, captor.getValue().getStatus());
        verify(chunkIngestionService, never()).ingestChunks(any(), any());
    }

    @Test
    void nonPdf_isRejected_beforeAnyStorageOrPersistence() {
        MockMultipartFile txt = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        assertThrows(UnsupportedOperationException.class, () -> service.upload(txt, null, uploader));

        verify(fileStorageService, never()).store(any(), anyString());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void emptyFile_isRejected() {
        MockMultipartFile empty = new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> service.upload(empty, null, uploader));
    }
}

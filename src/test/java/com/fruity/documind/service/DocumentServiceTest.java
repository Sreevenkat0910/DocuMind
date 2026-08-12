package com.fruity.documind.service;

import com.fruity.documind.entity.Document;
import com.fruity.documind.entity.User;
import com.fruity.documind.enums.DocumentStatus;
import com.fruity.documind.repository.DocumentPermissionRepository;
import com.fruity.documind.repository.DocumentRepository;
import com.fruity.documind.repository.UserRepository;
import com.fruity.documind.service.ChunkIngestionService.ChunkInput;
import com.fruity.documind.service.PdfParsingService.ParsedDocument;
import com.fruity.documind.service.PdfParsingService.ParsedPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Verifies the upload orchestration (status lifecycle + failure path) with mocked collaborators. */
class DocumentServiceTest {

    private DocumentRepository documentRepository;
    private UserRepository userRepository;
    private DocumentPermissionRepository documentPermissionRepository;
    private FileStorageService fileStorageService;
    private PdfParsingService pdfParsingService;
    private ChunkingService chunkingService;
    private ChunkIngestionService chunkIngestionService;
    private DocumentService service;

    @BeforeEach
    void setup() {
        documentRepository = mock(DocumentRepository.class);
        userRepository = mock(UserRepository.class);
        documentPermissionRepository = mock(DocumentPermissionRepository.class);
        fileStorageService = mock(FileStorageService.class);
        pdfParsingService = mock(PdfParsingService.class);
        chunkingService = mock(ChunkingService.class);
        chunkIngestionService = mock(ChunkIngestionService.class);
        service = new DocumentService(documentRepository, userRepository, documentPermissionRepository,
                fileStorageService, pdfParsingService, chunkingService, chunkIngestionService);

        // save() returns its argument (the same mutated Document instance) back.
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(new User()));
        when(fileStorageService.store(any(), anyString())).thenReturn("/tmp/stored.pdf");
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "report.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
    }

    @Test
    void happyPath_marksIndexed_andIngestsChunks() throws Exception {
        when(pdfParsingService.parse(any(byte[].class)))
                .thenReturn(new ParsedDocument(2, List.of(new ParsedPage(1, "a"), new ParsedPage(2, "b"))));
        when(chunkingService.chunk(any()))
                .thenReturn(List.of(new ChunkInput(0, "a", 1, 1), new ChunkInput(1, "b", 2, 1)));

        Document result = service.upload(pdf(), "My Title");

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
        when(chunkingService.chunk(any())).thenReturn(List.of()); // no extractable text

        Document result = service.upload(pdf(), null);

        assertEquals(DocumentStatus.INDEXED, result.getStatus());
        verify(chunkIngestionService, never()).ingestChunks(any(), any());
    }

    @Test
    void processingFailure_marksFailed_andRethrows() throws Exception {
        when(pdfParsingService.parse(any(byte[].class))).thenThrow(new IOException("corrupt pdf"));

        assertThrows(DocumentProcessingException.class, () -> service.upload(pdf(), null));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeastOnce()).save(captor.capture());
        assertEquals(DocumentStatus.FAILED, captor.getValue().getStatus());
        verify(chunkIngestionService, never()).ingestChunks(any(), any());
    }

    @Test
    void nonPdf_isRejected_beforeAnyStorageOrPersistence() {
        MockMultipartFile txt = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        assertThrows(UnsupportedOperationException.class, () -> service.upload(txt, null));

        verify(fileStorageService, never()).store(any(), anyString());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void emptyFile_isRejected() {
        MockMultipartFile empty = new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> service.upload(empty, null));
    }
}

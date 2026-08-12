package com.fruity.documind.service;

/** Thrown when a document is stored but its parse/chunk/embed pipeline fails. */
public class DocumentProcessingException extends RuntimeException {

    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}

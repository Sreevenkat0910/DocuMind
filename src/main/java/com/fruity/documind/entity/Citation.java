package com.fruity.documind.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "citations")
@Getter
@Setter
public class Citation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Lob
    @Column(nullable = false)
    private String chunkContent;

    @Column
    private Integer pageNumber;

    @Column
    private Double relevanceScore;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Citation other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
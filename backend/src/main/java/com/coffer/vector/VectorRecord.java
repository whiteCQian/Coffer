package com.coffer.vector;

import java.time.Instant;

/** A persisted embedding and its minimum metadata. */
public record VectorRecord(
        String documentId,
        Long fileId,
        Integer chunkIndex,
        String content,
        String embeddingModel,
        float[] vector,
        Instant updatedAt) {

    public VectorRecord(String documentId, String embeddingModel, float[] vector, Instant updatedAt) {
        this(documentId, null, null, null, embeddingModel, vector, updatedAt);
    }

    public VectorRecord {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("embeddingModel 不能为空");
        }
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("vector 不能为空");
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }
}

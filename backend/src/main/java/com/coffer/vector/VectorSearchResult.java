package com.coffer.vector;

/** One Redis Vector Set hit, ordered by descending vector similarity. */
public record VectorSearchResult(String documentId, double score) {
}

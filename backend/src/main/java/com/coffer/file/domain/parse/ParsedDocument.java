package com.coffer.file.domain.parse;

import java.util.List;

/** Source-addressable text. Offsets are one-based; an image chunk points to the original file. */
public record ParsedDocument(Long fileId, long revision, String format, String parserVersion,
                             ParseStatus status, String errorCode, List<Chunk> chunks) {
    public ParsedDocument { chunks = chunks == null ? List.of() : List.copyOf(chunks); }
    public record Chunk(String text, String sourceKind, int start, int end,
                        int startCharacter, int endCharacter) { }
    public String content() {
        return chunks.stream().map(Chunk::text).collect(java.util.stream.Collectors.joining("\n"));
    }
}

package com.kbagent.web.dto;

import com.kbagent.retrieval.RetrievedChunk;

/** 回答里引用的来源，前端拿它渲染可点击的引用角标。 */
public record SourceRef(
        int index,
        String fileName,
        String section,
        int chunkIndex,
        double score,
        String snippet) {

    private static final int SNIPPET_CHARS = 160;

    public static SourceRef of(int index, RetrievedChunk chunk) {
        return new SourceRef(
                index,
                chunk.fileName(),
                chunk.section(),
                chunk.chunkIndex(),
                chunk.score(),
                snippet(chunk.text()));
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").strip();
        return normalized.length() <= SNIPPET_CHARS
                ? normalized
                : normalized.substring(0, SNIPPET_CHARS) + "...";
    }
}

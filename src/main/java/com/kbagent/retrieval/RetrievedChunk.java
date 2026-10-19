package com.kbagent.retrieval;

/** 一次召回命中的片段。 */
public record RetrievedChunk(
        String embeddingId,
        String text,
        String fileName,
        String section,
        int chunkIndex,
        double score) {

    public RetrievedChunk withScore(double newScore) {
        return new RetrievedChunk(embeddingId, text, fileName, section, chunkIndex, newScore);
    }
}

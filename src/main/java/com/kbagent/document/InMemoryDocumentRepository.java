package com.kbagent.document;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** dev profile 用的内存实现，方便在没有数据库的机器上先跑起来。 */
@Repository
@Profile("dev")
public class InMemoryDocumentRepository implements DocumentRepository {

    private final Map<UUID, DocumentRecord> documents = new ConcurrentHashMap<>();
    private final Map<String, ChunkRecord> chunks = new ConcurrentHashMap<>();

    @Override
    public void save(DocumentRecord document) {
        documents.put(document.id(), document);
    }

    @Override
    public void updateProgress(UUID id, String status, String stage, int progress,
                               int chunkCount, String errorMessage) {
        documents.computeIfPresent(id,
                (key, existing) -> existing.withProgress(status, stage, progress, chunkCount, errorMessage));
    }

    @Override
    public Optional<DocumentRecord> findById(UUID id) {
        return Optional.ofNullable(documents.get(id));
    }

    @Override
    public Optional<DocumentRecord> findActiveByContentHash(String contentHash) {
        if (contentHash == null || contentHash.isBlank()) {
            return Optional.empty();
        }
        return documents.values().stream()
                .filter(doc -> contentHash.equals(doc.contentHash()))
                .filter(doc -> !DocumentRecord.STATUS_FAILED.equals(doc.status()))
                .max(Comparator.comparing(DocumentRecord::createdAt));
    }

    @Override
    public List<DocumentRecord> findAll() {
        return documents.values().stream()
                .sorted(Comparator.comparing(DocumentRecord::createdAt).reversed())
                .toList();
    }

    @Override
    public void saveChunks(List<ChunkRecord> newChunks) {
        newChunks.forEach(chunk -> chunks.put(chunk.id(), chunk));
    }

    @Override
    public List<String> findChunkIds(UUID documentId) {
        return chunks.values().stream()
                .filter(chunk -> chunk.documentId().equals(documentId))
                .map(ChunkRecord::id)
                .toList();
    }

    @Override
    public List<ChunkRecord> findAllChunks() {
        return List.copyOf(chunks.values());
    }

    @Override
    public void deleteDocument(UUID id) {
        documents.remove(id);
        chunks.values().removeIf(chunk -> chunk.documentId().equals(id));
    }
}

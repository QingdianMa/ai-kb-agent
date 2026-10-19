package com.kbagent.document;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!dev")
public class JdbcDocumentRepository implements DocumentRepository {

    private static final RowMapper<DocumentRecord> ROW_MAPPER = (rs, rowNum) -> new DocumentRecord(
            rs.getObject("id", UUID.class),
            rs.getString("file_name"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getString("content_hash"),
            rs.getString("status"),
            rs.getString("stage"),
            rs.getInt("progress"),
            rs.getInt("chunk_count"),
            rs.getString("error_message"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(DocumentRecord d) {
        jdbc.update("""
                INSERT INTO kb_document
                    (id, file_name, content_type, size_bytes, content_hash,
                     status, stage, progress, chunk_count, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, d.id(), d.fileName(), d.contentType(), d.sizeBytes(), d.contentHash(), d.status(),
                d.stage(), d.progress(), d.chunkCount(), d.errorMessage());
    }

    @Override
    public Optional<DocumentRecord> findActiveByContentHash(String contentHash) {
        if (contentHash == null || contentHash.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT * FROM kb_document
                 WHERE content_hash = ? AND status <> 'FAILED'
                 ORDER BY created_at DESC LIMIT 1
                """, ROW_MAPPER, contentHash).stream().findFirst();
    }

    @Override
    public void updateProgress(UUID id, String status, String stage, int progress,
                               int chunkCount, String errorMessage) {
        jdbc.update("""
                UPDATE kb_document
                   SET status = ?, stage = ?, progress = ?, chunk_count = ?,
                       error_message = ?, updated_at = now()
                 WHERE id = ?
                """, status, stage, progress, chunkCount, errorMessage, id);
    }

    @Override
    public Optional<DocumentRecord> findById(UUID id) {
        return jdbc.query("SELECT * FROM kb_document WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    @Override
    public List<DocumentRecord> findAll() {
        return jdbc.query("SELECT * FROM kb_document ORDER BY created_at DESC", ROW_MAPPER);
    }

    @Override
    public void saveChunks(List<ChunkRecord> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO kb_chunk (id, document_id, chunk_index, section, content)
                VALUES (?, ?, ?, ?, ?)
                """, chunks, chunks.size(), (ps, c) -> {
            ps.setString(1, c.id());
            ps.setObject(2, c.documentId());
            ps.setInt(3, c.chunkIndex());
            ps.setString(4, c.section());
            ps.setString(5, c.content());
        });
    }

    @Override
    public List<String> findChunkIds(UUID documentId) {
        return jdbc.queryForList("SELECT id FROM kb_chunk WHERE document_id = ?", String.class, documentId);
    }

    @Override
    public List<ChunkRecord> findAllChunks() {
        return jdbc.query("""
                SELECT id, document_id, chunk_index, section, content FROM kb_chunk
                """, (rs, rowNum) -> new ChunkRecord(
                rs.getString("id"),
                rs.getObject("document_id", UUID.class),
                rs.getInt("chunk_index"),
                rs.getString("section"),
                rs.getString("content")));
    }

    @Override
    public void deleteDocument(UUID id) {
        jdbc.update("DELETE FROM kb_document WHERE id = ?", id);
    }
}

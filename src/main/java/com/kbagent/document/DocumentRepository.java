package com.kbagent.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文档元数据的存储接口。默认走 Postgres，dev profile 走内存实现。
 */
public interface DocumentRepository {

    void save(DocumentRecord document);

    /** 更新处理状态与进度。异步入库的每一步都会调用它。 */
    void updateProgress(UUID id, String status, String stage, int progress,
                        int chunkCount, String errorMessage);

    Optional<DocumentRecord> findById(UUID id);

    /**
     * 按内容哈希查找一份「可用或处理中」的文档。
     * 失败的不算，否则一次失败之后就再也传不进来了。
     */
    Optional<DocumentRecord> findActiveByContentHash(String contentHash);

    List<DocumentRecord> findAll();

    void saveChunks(List<ChunkRecord> chunks);

    List<String> findChunkIds(UUID documentId);

    /** 取出全部片段，用于重建关键词索引。 */
    List<ChunkRecord> findAllChunks();

    void deleteDocument(UUID id);
}

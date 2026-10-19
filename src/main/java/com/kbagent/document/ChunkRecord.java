package com.kbagent.document;

import java.util.UUID;

/**
 * 切分后的一个片段。
 * id 用的是向量库里那条记录的主键，这样删文档时可以按 id 精确清理向量。
 */
public record ChunkRecord(
        String id,
        UUID documentId,
        int chunkIndex,
        String section,
        String content) {
}

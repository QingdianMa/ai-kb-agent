package com.kbagent.document;

import java.time.Instant;
import java.util.UUID;

/**
 * 一份入库文档的元信息。
 *
 * status 是状态机的当前节点，stage 和 progress 是给用户看的进度描述——
 * 状态回答"能不能用"，进度回答"还要等多久"，两者用途不同，不能互相替代。
 */
public record DocumentRecord(
        UUID id,
        String fileName,
        String contentType,
        long sizeBytes,
        /** 文件内容的 SHA-256，用于判断是否已经入库过 */
        String contentHash,
        String status,
        String stage,
        int progress,
        int chunkCount,
        String errorMessage,
        Instant createdAt) {

    /** 已接收，排队等待处理 */
    public static final String STATUS_PENDING = "PENDING";
    /** 正在处理：解析 → 切分 → 向量化 */
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    public DocumentRecord withProgress(String newStatus, String newStage, int newProgress,
                                       int newChunkCount, String newErrorMessage) {
        return new DocumentRecord(id, fileName, contentType, sizeBytes, contentHash, newStatus,
                newStage, newProgress, newChunkCount, newErrorMessage, createdAt);
    }
}

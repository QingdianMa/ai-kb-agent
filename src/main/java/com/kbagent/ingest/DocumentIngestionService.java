package com.kbagent.ingest;

import com.kbagent.config.AppProperties;
import com.kbagent.config.AsyncConfig;
import com.kbagent.document.ChunkRecord;
import com.kbagent.document.DocumentRecord;
import com.kbagent.document.DocumentRepository;
import com.kbagent.retrieval.KeywordIndex;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * 入库流水线：解析 → 章节切分 → 定长切分 → 向量化 → 落库。
 *
 * 提供两个入口，对应两种使用场景：
 *   submit()    —— 异步。界面上传用这个：立刻返回 PENDING，后台线程慢慢处理。
 *   ingestNow() —— 同步。批量导入和评测用这个：调用方需要等结果才继续。
 *
 * 为什么必须有异步版本：一份 68,484 字的文档要 6.4 秒才能入库，
 * 同步的话用户的浏览器要转圈 6 秒以上，而且占着一个 Tomcat 请求线程不放。
 * 并发几个上传，请求线程池就被占满了。
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private static final int MAX_SEGMENT_CHARS = 800;
    private static final int SEGMENT_OVERLAP_CHARS = 120;
    private static final int MAX_ERROR_MESSAGE_CHARS = 500;
    private static final int MAX_EMBED_ATTEMPTS = 3;
    private static final int MIN_CHARS_PER_KB = 5;

    /** 进度条的区间划分：解析 5%，切分到 15%，向量化占 15%~95%，最后 100% */
    private static final int PROGRESS_AFTER_PARSE = 5;
    private static final int PROGRESS_AFTER_SPLIT = 15;
    private static final int PROGRESS_BEFORE_DONE = 95;

    private final TextExtractor textExtractor;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentRepository repository;
    private final KeywordIndex keywordIndex;
    private final ThreadPoolTaskExecutor documentExecutor;
    private final int embeddingBatchSize;

    public DocumentIngestionService(TextExtractor textExtractor,
                                    EmbeddingModel embeddingModel,
                                    EmbeddingStore<TextSegment> embeddingStore,
                                    DocumentRepository repository,
                                    KeywordIndex keywordIndex,
                                    @Qualifier(AsyncConfig.DOCUMENT_EXECUTOR) ThreadPoolTaskExecutor documentExecutor,
                                    AppProperties properties) {
        this.textExtractor = textExtractor;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.repository = repository;
        this.keywordIndex = keywordIndex;
        this.documentExecutor = documentExecutor;
        this.embeddingBatchSize = properties.getEmbedding().getBatchSize();
    }

    /**
     * 入库结果。
     * skipped=true 表示"内容与已有文档相同，这次没有重复入库"——
     * 这是「一键导入」能反复执行而不产生重复数据的依据。
     */
    public record SubmitResult(DocumentRecord document, boolean skipped) {
    }

    /** 异步入库：立刻返回 PENDING 记录，实际处理在后台线程池里跑。 */
    public SubmitResult submit(String fileName, String contentType, byte[] bytes, boolean skipIfExists) {
        Optional<DocumentRecord> duplicate = findDuplicate(bytes, skipIfExists);
        if (duplicate.isPresent()) {
            log.info("[入库] 内容与已有文档相同，跳过: {}（已有: {}）",
                    fileName, duplicate.get().fileName());
            return new SubmitResult(duplicate.get(), true);
        }

        DocumentRecord pending = createPending(fileName, contentType, bytes);
        try {
            documentExecutor.execute(() -> process(pending.id(), fileName, bytes));
        } catch (RejectedExecutionException e) {
            // 队列满了：立刻把状态改成失败，让用户看到明确原因，而不是一直等
            repository.updateProgress(pending.id(), DocumentRecord.STATUS_FAILED,
                    "队列已满", 0, 0, "服务器繁忙，请稍后重试");
            throw new IllegalStateException("服务器繁忙，请稍后重试");
        }
        return new SubmitResult(pending, false);
    }

    /** 同步入库：批量导入、评测等需要立刻拿到结果的场景。 */
    public SubmitResult ingestNow(String fileName, String contentType, byte[] bytes, boolean skipIfExists) {
        Optional<DocumentRecord> duplicate = findDuplicate(bytes, skipIfExists);
        if (duplicate.isPresent()) {
            return new SubmitResult(duplicate.get(), true);
        }
        DocumentRecord pending = createPending(fileName, contentType, bytes);
        return new SubmitResult(process(pending.id(), fileName, bytes), false);
    }

    public List<DocumentRecord> list() {
        return repository.findAll();
    }

    public Optional<DocumentRecord> findById(UUID id) {
        return repository.findById(id);
    }

    public void delete(UUID documentId) {
        List<String> chunkIds = repository.findChunkIds(documentId);
        if (!chunkIds.isEmpty()) {
            // 元数据和向量必须一起删，否则会出现"搜得到但看不到出处"的幽灵片段
            embeddingStore.removeAll(chunkIds);
        }
        repository.deleteDocument(documentId);
        keywordIndex.markDirty();
        log.info("已删除文档 {}（清理 {} 个片段）", documentId, chunkIds.size());
    }

    private DocumentRecord createPending(String fileName, String contentType, byte[] bytes) {
        UUID documentId = UUID.randomUUID();
        DocumentRecord record = new DocumentRecord(documentId, fileName, contentType, bytes.length,
                sha256Hex(bytes), DocumentRecord.STATUS_PENDING, "排队等待处理",
                0, 0, null, Instant.now());
        repository.save(record);
        log.info("[入库] 已接收: {}（{} KB），文档ID={}", fileName, bytes.length / 1024, documentId);
        return record;
    }

    /** 判断这份内容是否已经入库过。失败过的不算，否则一次失败就再也传不进来了。 */
    private Optional<DocumentRecord> findDuplicate(byte[] bytes, boolean skipIfExists) {
        if (!skipIfExists) {
            return Optional.empty();
        }
        return repository.findActiveByContentHash(sha256Hex(bytes));
    }

    private String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256 实现", e);
        }
    }

    /**
     * 真正的处理逻辑，在后台线程或调用线程上执行。
     * 注意这里**必须捕获所有异常**：跑在后台线程上的话，抛出去的异常没人接，
     * 用户会永远停在一个"处理中"的假象里。
     */
    private DocumentRecord process(UUID documentId, String fileName, byte[] bytes) {
        long startedAt = System.currentTimeMillis();
        try {
            update(documentId, DocumentRecord.STATUS_PROCESSING, "解析文档", PROGRESS_AFTER_PARSE);
            long stepAt = System.currentTimeMillis();
            String text = textExtractor.extract(fileName, bytes);
            log.info("[入库] 解析完成: {} 字符，耗时 {} ms", text.length(), elapsed(stepAt));
            if (text.isBlank()) {
                throw new IllegalStateException("解析后内容为空，可能是扫描件或加密文档（需要接入 OCR）");
            }
            warnIfLikelyScanned(fileName, text.length(), bytes.length);

            update(documentId, DocumentRecord.STATUS_PROCESSING, "切分文档", PROGRESS_AFTER_SPLIT);
            stepAt = System.currentTimeMillis();
            List<TextSegment> segments = buildSegments(documentId, fileName, text);
            if (segments.isEmpty()) {
                throw new IllegalStateException("切分后没有产生任何有效片段");
            }
            log.info("[入库] 切分完成: {} 个片段，耗时 {} ms", segments.size(), elapsed(stepAt));

            List<String> vectorIds = embedAndStore(documentId, segments);

            List<ChunkRecord> chunks = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                chunks.add(new ChunkRecord(vectorIds.get(i), documentId, i,
                        segment.metadata().getString("section"), segment.text()));
            }
            repository.saveChunks(chunks);
            keywordIndex.markDirty();
            repository.updateProgress(documentId, DocumentRecord.STATUS_READY,
                    "完成", 100, chunks.size(), null);
            log.info("[入库] 完成: {} -> {} 个片段，总耗时 {} ms",
                    fileName, chunks.size(), elapsed(startedAt));
            return repository.findById(documentId).orElseThrow();
        } catch (Exception e) {
            log.warn("[入库] 失败: {}，已耗时 {} ms", fileName, elapsed(startedAt), e);
            repository.updateProgress(documentId, DocumentRecord.STATUS_FAILED,
                    "处理失败", 0, 0, truncate(e.getMessage()));
            return repository.findById(documentId)
                    .orElseThrow(() -> new IllegalStateException("文档记录丢失: " + documentId));
        }
    }

    private void update(UUID documentId, String status, String stage, int progress) {
        repository.updateProgress(documentId, status, stage, progress, 0, null);
    }

    /**
     * 分批向量化再写库。
     * 一份几十页的文档可能有上百个片段，一次性塞进一个请求很容易被服务端限流或超时，
     * 所以按批次发，并且每批失败后指数退避重试。
     */
    private List<String> embedAndStore(UUID documentId, List<TextSegment> segments) {
        List<String> vectorIds = new ArrayList<>(segments.size());
        int total = segments.size();
        int batchCount = (total + embeddingBatchSize - 1) / embeddingBatchSize;

        for (int from = 0; from < total; from += embeddingBatchSize) {
            int to = Math.min(from + embeddingBatchSize, total);
            List<TextSegment> batch = segments.subList(from, to);
            int batchNo = from / embeddingBatchSize + 1;

            long batchAt = System.currentTimeMillis();
            List<Embedding> embeddings = embedWithRetry(batch, batchNo, batchCount);
            vectorIds.addAll(embeddingStore.addAll(embeddings, batch));
            log.info("[入库] 向量化进度: {}/{} 片段（第 {}/{} 批），本批耗时 {} ms",
                    to, total, batchNo, batchCount, elapsed(batchAt));

            // 把进度从 15% 线性推进到 95%，前端就能看到进度条在动
            int span = PROGRESS_BEFORE_DONE - PROGRESS_AFTER_SPLIT;
            int progress = PROGRESS_AFTER_SPLIT + span * to / total;
            update(documentId, DocumentRecord.STATUS_PROCESSING,
                    "向量化 " + to + "/" + total + " 片段", progress);
        }
        return vectorIds;
    }

    private List<Embedding> embedWithRetry(List<TextSegment> batch, int batchNo, int batchCount) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_EMBED_ATTEMPTS; attempt++) {
            try {
                return embeddingModel.embedAll(batch).content();
            } catch (RuntimeException e) {
                lastError = e;
                long backoffMs = 1000L * (1L << (attempt - 1));
                log.warn("[入库] 第 {}/{} 批（{} 个片段）向量化失败，第 {}/{} 次尝试: {}",
                        batchNo, batchCount, batch.size(), attempt, MAX_EMBED_ATTEMPTS, e.getMessage());
                if (attempt < MAX_EMBED_ATTEMPTS) {
                    log.info("[入库] {} ms 后重试", backoffMs);
                    sleep(backoffMs);
                }
            }
        }
        throw lastError;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量化重试被中断", e);
        }
    }

    private List<TextSegment> buildSegments(UUID documentId, String fileName, String text) {
        DocumentSplitter splitter = DocumentSplitters.recursive(MAX_SEGMENT_CHARS, SEGMENT_OVERLAP_CHARS);
        List<TextSegment> segments = new ArrayList<>();
        int chunkIndex = 0;

        for (ContentSectioner.Section section : ContentSectioner.split(text)) {
            Document sectionDocument = Document.from(section.text());
            for (TextSegment raw : splitter.split(sectionDocument)) {
                Metadata metadata = Metadata.from(Map.of(
                        "document_id", documentId.toString(),
                        "file_name", fileName,
                        "section", section.title() == null ? "" : section.title(),
                        "chunk_index", String.valueOf(chunkIndex)));
                segments.add(TextSegment.from(raw.text(), metadata));
                chunkIndex++;
            }
        }
        return segments;
    }

    /**
     * 识别"扫描件"这种隐性质量问题：265 KB 的 PDF 只抽出 1028 个字符，
     * 基本可以断定是图片型 PDF，没有文字层。
     *
     * 判断依据是「文件体积与文本量的比例」，而且**只对 PDF / Word 生效**——
     * 这两类格式本该有文字层。Markdown、txt 本来就是纯文本，短是正常的，
     * 之前这里加了"少于 500 字就告警"的绝对阈值，导致几百字的 md 文件被误报。
     */
    private void warnIfLikelyScanned(String fileName, int textLength, int fileBytes) {
        if (!isBinaryDocument(fileName)) {
            return;
        }
        int fileKb = Math.max(1, fileBytes / 1024);
        if (textLength < fileKb * MIN_CHARS_PER_KB) {
            log.warn("[入库] 注意: {} 有 {} KB，却只解析出 {} 个字符，大概率是扫描件或图片型 PDF。"
                            + "当前版本没有 OCR，检索效果会很差，建议换一份带文字层的文件。",
                    fileName, fileKb, textLength);
        }
    }

    /** PDF 和 Word 才需要"文字层"这个概念；纯文本格式不需要判断。 */
    private boolean isBinaryDocument(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc");
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_MESSAGE_CHARS
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE_CHARS);
    }

    private long elapsed(long sinceMillis) {
        return System.currentTimeMillis() - sinceMillis;
    }
}

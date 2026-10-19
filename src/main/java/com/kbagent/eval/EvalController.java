package com.kbagent.eval;

import com.kbagent.config.AppProperties;
import com.kbagent.document.DocumentRecord;
import com.kbagent.ingest.DocumentIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private static final Logger log = LoggerFactory.getLogger(EvalController.class);

    private final EvalSetGenerator generator;
    private final EvalRunner runner;
    private final DocumentIngestionService ingestionService;
    private final AppProperties properties;

    public EvalController(EvalSetGenerator generator,
                          EvalRunner runner,
                          DocumentIngestionService ingestionService,
                          AppProperties properties) {
        this.generator = generator;
        this.runner = runner;
        this.ingestionService = ingestionService;
        this.properties = properties;
    }

    /**
     * 从 docs/ 目录反向生成评测集，结果写到 eval/qa-set-generated.json。
     * 每条约 1 秒，默认 40 条，所以整个请求大约 1 分钟。
     * 想先小规模试，加 ?limit=3。
     */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestParam(name = "limit", required = false) Integer limit) {
        return generator.generate(limit);
    }

    /**
     * 跑消融实验：同一套评测集跑四种检索配置，返回对比数据。
     * 需要先把 docs/ 里的文档上传进知识库，否则检索不到东西。
     */
    @PostMapping("/run")
    public Map<String, Object> run(
            @RequestParam(name = "path", required = false) String path) {
        String evalPath = (path == null || path.isBlank()) ? "eval/qa-set-final.json" : path;
        // 评测必须等所有文档入库完成，否则会用一份残缺的知识库跑，结果全错
        waitForIngestion();
        return runner.run(evalPath);
    }

    /**
     * 把 docs/ 目录下的 Markdown 全部灌进知识库。
     * 用异步入库：接口立刻返回，前端能看到每个文档的进度条。
     */
    @PostMapping("/ingest-docs")
    public Map<String, Object> ingestDocs(
            @RequestParam(name = "path", required = false) String path) {
        Path docsDir = Path.of(path == null || path.isBlank()
                ? properties.getIngest().getSourceDir() : path);
        if (!Files.isDirectory(docsDir)) {
            throw new IllegalStateException(
                    "找不到知识库目录: " + docsDir.toAbsolutePath() + "（把要入库的资料放进这个目录）");
        }

        List<Map<String, Object>> submitted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try (Stream<Path> files = Files.list(docsDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList()) {
                // 异步提交 + 按内容去重：反复点「一键导入」只会补新增和改动过的文件
                DocumentIngestionService.SubmitResult result = ingestionService.submit(
                        file.getFileName().toString(), "text/markdown", Files.readAllBytes(file), true);
                if (result.skipped()) {
                    skipped.add(file.getFileName().toString());
                    continue;
                }
                DocumentRecord record = result.document();
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("file", record.fileName());
                cell.put("status", record.status());
                submitted.add(cell);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取文档目录失败", e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submitted", submitted.size());
        result.put("skipped", skipped);
        result.put("detail", submitted);
        result.put("note", skipped.isEmpty()
                ? "已提交后台处理，请在文档列表查看进度"
                : "已跳过 " + skipped.size() + " 个未改动的文件，只处理新增和改动过的");
        return result;
    }

    /** 等待所有文档处理结束，最长 3 分钟。 */
    private void waitForIngestion() {
        List<DocumentRecord> documents = ingestionService.list();
        if (documents.isEmpty()) {
            throw new IllegalStateException("知识库为空，请先导入文档再跑评测");
        }
        long deadline = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < deadline) {
            boolean busy = ingestionService.list().stream().anyMatch(doc ->
                    DocumentRecord.STATUS_PENDING.equals(doc.status())
                            || DocumentRecord.STATUS_PROCESSING.equals(doc.status()));
            if (!busy) {
                long failed = ingestionService.list().stream()
                        .filter(doc -> DocumentRecord.STATUS_FAILED.equals(doc.status()))
                        .count();
                if (failed > 0) {
                    // 不直接报错：部分文档失败也可以评测，但要让调用方知道结果会偏低
                    log.warn("[评测] 有 {} 个文档入库失败，评测结果会偏低，请检查文档列表", failed);
                }
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待入库被中断", e);
            }
        }
        throw new IllegalStateException("等待文档入库超时（3 分钟），请检查文档列表里的失败原因");
    }
}

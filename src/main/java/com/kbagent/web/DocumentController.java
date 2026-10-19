package com.kbagent.web;

import com.kbagent.document.DocumentRecord;
import com.kbagent.ingest.DocumentIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService ingestionService;

    public DocumentController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "force", required = false) String force) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        // 默认按内容去重：同一个文件传两次不会产生重复片段。加 ?force=true 可强制重新入库
        boolean skipIfExists = !"true".equalsIgnoreCase(force);
        DocumentIngestionService.SubmitResult result = ingestionService.submit(
                fileName, file.getContentType(), file.getBytes(), skipIfExists);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("document", result.document());
        body.put("skipped", result.skipped());
        body.put("message", result.skipped()
                ? "该文档内容已存在，已跳过（如需重新入库请加 force=true）"
                : "已提交后台处理，请在列表中查看进度");

        // 跳过时返回 200，实际提交时返回 202 Accepted（请求已接收、处理进行中）
        return result.skipped() ? ResponseEntity.ok(body) : ResponseEntity.accepted().body(body);
    }

    @GetMapping
    public List<DocumentRecord> list() {
        return ingestionService.list();
    }

    /** 查询单个文档的处理进度，供前端轮询。 */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentRecord> get(@PathVariable UUID id) {
        return ingestionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ingestionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

package com.kbagent.web;

import com.kbagent.infra.FileStorage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** 图片上传，只有管理员能调用（/api/admin/** 默认需要管理员口令）。 */
@RestController
@RequestMapping("/api/admin")
public class UploadController {

    private final FileStorage fileStorage;

    public UploadController(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @PostMapping("/upload")
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) {
        return Map.of("path", fileStorage.save(file));
    }
}

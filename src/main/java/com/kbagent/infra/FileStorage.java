package com.kbagent.infra;

import com.kbagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 本地文件存储，目前只用于上传成员照片。
 *
 * 三条保护措施，每一条都对应一个真实会发生的坑：
 *   1. 限制扩展名白名单——否则有人上传 .jsp / .html，可能变成挂马点
 *   2. 限制单文件大小——团队照片动辄几 MB，不限制会拖慢页面、塞满磁盘
 *   3. 文件名用 UUID 重命名——避免覆盖已有文件，也避免中文、空格、特殊字符带来的路径问题
 *
 * 存储目录必须挂到容器卷上。存容器内部的话，每次重新部署照片就全没了——
 * 这和数据库数据要持久化是同一个道理。
 */
@Component
public class FileStorage {

    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final long MAX_BYTES = 2 * 1024 * 1024L;

    private final Path root;

    public FileStorage(AppProperties properties) {
        this.root = Path.of(properties.getStorage().getUploadDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建上传目录: " + root, e);
        }
        log.info("[存储] 上传目录: {}", root);
    }

    public Path root() {
        return root;
    }

    public String save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("图片超过 2MB，请压缩后再上传");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("只支持 jpg / png / webp 格式");
        }

        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        try {
            file.transferTo(root.resolve(fileName));
        } catch (IOException e) {
            throw new UncheckedIOException("保存文件失败", e);
        }
        log.info("[存储] 已保存 {}（{} KB）", fileName, file.getSize() / 1024);
        return "/uploads/" + fileName;
    }

    /** 删除文件。删成员时要一起清理，否则服务器上会堆积孤儿图片。 */
    public void delete(String publicPath) {
        if (publicPath == null || !publicPath.startsWith("/uploads/")) {
            return;
        }
        Path target = root.resolve(publicPath.substring("/uploads/".length())).normalize();
        // 防止通过 ../ 跳出上传目录
        if (!target.startsWith(root)) {
            log.warn("[存储] 拒绝删除越界路径: {}", publicPath);
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("[存储] 删除文件失败: {} - {}", target, e.getMessage());
        }
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

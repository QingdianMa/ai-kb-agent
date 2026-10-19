package com.kbagent.lab;

import com.kbagent.infra.FileStorage;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 站点内容的写接口，只有管理员能访问。
 * 路径统一放在 /api/admin/lab/** 下，这样权限过滤器的规则只需要判断前缀，简单不易错。
 */
@RestController
@RequestMapping("/api/admin/lab")
public class LabAdminController {

    private final LabRepository repository;
    private final FileStorage fileStorage;

    public LabAdminController(LabRepository repository, FileStorage fileStorage) {
        this.repository = repository;
        this.fileStorage = fileStorage;
    }

    @PostMapping("/members")
    public LabMember saveMember(@RequestBody LabMember member) {
        // 换照片时把旧文件删掉，否则服务器上会越堆越多孤儿图片
        //
        // 注意这里的写法：先 findFirst() 拿到成员对象，再用 Optional.map 取 photoPath。
        // 不能写成 stream().map(LabMember::photoPath).findFirst()——
        // findFirst() 内部用 Optional.of()，而 photoPath 可能是 null，
        // Optional.of(null) 会抛 NullPointerException（编辑没有照片的成员时必崩）。
        Optional<LabMember> existing = member.id() == null
                ? Optional.empty()
                : repository.listMembers().stream()
                        .filter(item -> item.id().equals(member.id()))
                        .findFirst();
        String oldPhoto = existing.map(LabMember::photoPath).orElse(null);

        LabMember saved = repository.saveMember(member);
        if (oldPhoto != null && !oldPhoto.equals(saved.photoPath())) {
            fileStorage.delete(oldPhoto);
        }
        return saved;
    }

    @DeleteMapping("/members/{id}")
    public Map<String, Object> deleteMember(@PathVariable UUID id) {
        // 删记录的同时删照片，否则磁盘上会留下没人引用的文件
        repository.listMembers().stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .ifPresent(item -> fileStorage.delete(item.photoPath()));
        repository.deleteMember(id);
        return Map.of("deleted", id.toString());
    }

    /** 批量保存站点文本，后台点一次"保存"提交全部字段。 */
    @PutMapping("/content")
    public Map<String, String> saveContent(@RequestBody Map<String, String> values) {
        values.forEach(repository::saveContent);
        return repository.loadContent();
    }

    @PostMapping("/publications")
    public LabPublication savePublication(@RequestBody LabPublication publication) {
        return repository.savePublication(publication);
    }

    @DeleteMapping("/publications/{id}")
    public Map<String, Object> deletePublication(@PathVariable UUID id) {
        repository.deletePublication(id);
        return Map.of("deleted", id.toString());
    }
}

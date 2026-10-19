package com.kbagent.lab;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实验室门户的内容存储。
 * 和文档仓库一样，默认走 Postgres，dev profile 走内存实现，这样本地不用装数据库也能开发。
 */
public interface LabRepository {

    List<LabMember> listMembers();

    LabMember saveMember(LabMember member);

    void deleteMember(UUID id);

    /** 站点文本内容，key 形如 home.intro、research.direction.1 */
    Map<String, String> loadContent();

    void saveContent(String key, String value);

    List<LabPublication> listPublications();

    LabPublication savePublication(LabPublication publication);

    void deletePublication(UUID id);
}

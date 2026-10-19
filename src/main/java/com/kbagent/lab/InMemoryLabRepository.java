package com.kbagent.lab;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * dev profile 用的内存实现。初始内容由 LabDataSeeder 统一写入，
 * 这样本地和生产走的是同一套种子逻辑，不会出现"本地有内容、上线是空的"。
 */
@Repository
@Profile("dev")
public class InMemoryLabRepository implements LabRepository {

    private final Map<UUID, LabMember> members = new ConcurrentHashMap<>();
    private final Map<String, String> content = new ConcurrentHashMap<>();
    private final Map<UUID, LabPublication> publications = new ConcurrentHashMap<>();

    @Override
    public List<LabMember> listMembers() {
        return members.values().stream()
                .sorted(Comparator.comparingInt(LabMember::sortOrder)
                        .thenComparing(member -> member.name() == null ? "" : member.name()))
                .toList();
    }

    @Override
    public LabMember saveMember(LabMember member) {
        UUID id = member.id() == null ? UUID.randomUUID() : member.id();
        LabMember saved = new LabMember(id, member.name(), member.role(), member.title(),
                member.photoPath(), member.bio(), member.email(), member.sortOrder());
        members.put(id, saved);
        return saved;
    }

    @Override
    public void deleteMember(UUID id) {
        members.remove(id);
    }

    @Override
    public Map<String, String> loadContent() {
        return new LinkedHashMap<>(content);
    }

    @Override
    public void saveContent(String key, String value) {
        content.put(key, value);
    }

    @Override
    public List<LabPublication> listPublications() {
        return publications.values().stream()
                .sorted(Comparator.comparingInt(LabPublication::sortOrder))
                .toList();
    }

    @Override
    public LabPublication savePublication(LabPublication publication) {
        UUID id = publication.id() == null ? UUID.randomUUID() : publication.id();
        LabPublication saved = new LabPublication(id, publication.title(), publication.authors(),
                publication.venue(), publication.year(), publication.url(), publication.sortOrder());
        publications.put(id, saved);
        return saved;
    }

    @Override
    public void deletePublication(UUID id) {
        publications.remove(id);
    }
}

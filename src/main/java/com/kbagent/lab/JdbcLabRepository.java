package com.kbagent.lab;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@Profile("!dev")
public class JdbcLabRepository implements LabRepository {

    private static final RowMapper<LabMember> MEMBER_MAPPER = (rs, rowNum) -> new LabMember(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("role"),
            rs.getString("title"),
            rs.getString("photo_path"),
            rs.getString("bio"),
            rs.getString("email"),
            rs.getInt("sort_order"));

    private static final RowMapper<LabPublication> PUBLICATION_MAPPER = (rs, rowNum) -> new LabPublication(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("authors"),
            rs.getString("venue"),
            (Integer) rs.getObject("year"),
            rs.getString("url"),
            rs.getInt("sort_order"));

    private final JdbcTemplate jdbc;

    public JdbcLabRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LabMember> listMembers() {
        return jdbc.query("SELECT * FROM lab_member ORDER BY sort_order, name", MEMBER_MAPPER);
    }

    @Override
    public LabMember saveMember(LabMember member) {
        UUID id = member.id() == null ? UUID.randomUUID() : member.id();
        // 用 ON CONFLICT 做 upsert：新增和修改走同一个接口，前端不用区分
        jdbc.update("""
                INSERT INTO lab_member (id, name, role, title, photo_path, bio, email, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name, role = EXCLUDED.role, title = EXCLUDED.title,
                    photo_path = EXCLUDED.photo_path, bio = EXCLUDED.bio,
                    email = EXCLUDED.email, sort_order = EXCLUDED.sort_order,
                    updated_at = now()
                """, id, member.name(), member.role(), member.title(), member.photoPath(),
                member.bio(), member.email(), member.sortOrder());
        return new LabMember(id, member.name(), member.role(), member.title(),
                member.photoPath(), member.bio(), member.email(), member.sortOrder());
    }

    @Override
    public void deleteMember(UUID id) {
        jdbc.update("DELETE FROM lab_member WHERE id = ?", id);
    }

    @Override
    public Map<String, String> loadContent() {
        Map<String, String> content = new LinkedHashMap<>();
        // 显式声明成 RowCallbackHandler，否则 lambda 会在几个重载之间产生歧义
        jdbc.query("SELECT key, value FROM lab_content",
                (RowCallbackHandler) rs -> content.put(rs.getString("key"), rs.getString("value")));
        return content;
    }

    @Override
    public void saveContent(String key, String value) {
        jdbc.update("""
                INSERT INTO lab_content (key, value) VALUES (?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                """, key, value);
    }

    @Override
    public List<LabPublication> listPublications() {
        return jdbc.query("SELECT * FROM lab_publication ORDER BY sort_order, year DESC", PUBLICATION_MAPPER);
    }

    @Override
    public LabPublication savePublication(LabPublication publication) {
        UUID id = publication.id() == null ? UUID.randomUUID() : publication.id();
        jdbc.update("""
                INSERT INTO lab_publication (id, title, authors, venue, year, url, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    title = EXCLUDED.title, authors = EXCLUDED.authors, venue = EXCLUDED.venue,
                    year = EXCLUDED.year, url = EXCLUDED.url, sort_order = EXCLUDED.sort_order
                """, id, publication.title(), publication.authors(), publication.venue(),
                publication.year(), publication.url(), publication.sortOrder());
        return new LabPublication(id, publication.title(), publication.authors(), publication.venue(),
                publication.year(), publication.url(), publication.sortOrder());
    }

    @Override
    public void deletePublication(UUID id) {
        jdbc.update("DELETE FROM lab_publication WHERE id = ?", id);
    }
}

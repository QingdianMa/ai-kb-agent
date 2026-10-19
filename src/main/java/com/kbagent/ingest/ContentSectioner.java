package com.kbagent.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 结构感知切分的第一步：先按标题把文档切成"章节"，再对每章做定长递归切分。
 * <p>
 * 为什么值得做：固定长度切分会把标题和正文切散，检索到的片段经常"没有上下文"，
 * 而带上章节名之后，既能提升召回质量，也能在回答里给出更可信的引用位置。
 */
public final class ContentSectioner {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+\\S.*$");
    private static final Pattern NUMBERED_HEADING =
            Pattern.compile("^\\s*(第[一二三四五六七八九十百]+[章节节部分]|[0-9]+(\\.[0-9]+){0,3}[、.\\s])\\S.{0,50}$");
    private static final int MAX_HEADING_LENGTH = 80;

    private ContentSectioner() {
    }

    public record Section(String title, String text) {
    }

    public static List<Section> split(String rawText) {
        List<Section> sections = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) {
            return sections;
        }

        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n');
        String currentTitle = null;
        StringBuilder buffer = new StringBuilder();

        for (String line : normalized.split("\n", -1)) {
            if (isHeading(line)) {
                flush(sections, currentTitle, buffer);
                currentTitle = line.trim();
            } else {
                buffer.append(line).append('\n');
            }
        }
        flush(sections, currentTitle, buffer);

        // 整篇没有标题时，退化成"一个巨大的章节"，后面交给递归切分器处理
        if (sections.isEmpty()) {
            sections.add(new Section(null, normalized));
        }
        return sections;
    }

    private static void flush(List<Section> sections, String title, StringBuilder buffer) {
        String text = buffer.toString().strip();
        buffer.setLength(0);
        if (!text.isEmpty()) {
            sections.add(new Section(title, text));
        }
    }

    private static boolean isHeading(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_HEADING_LENGTH) {
            return false;
        }
        return MARKDOWN_HEADING.matcher(trimmed).matches()
                || NUMBERED_HEADING.matcher(trimmed).matches();
    }
}

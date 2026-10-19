package com.kbagent.ingest;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把各种格式的文件统一抽成纯文本。
 * 第一周先覆盖 pdf / docx / md / txt；扫描件（图片型 PDF）需要 OCR，留到后面做。
 */
@Component
public class TextExtractor {

    public String extract(String fileName, byte[] bytes) {
        String extension = extensionOf(fileName);
        try {
            return switch (extension) {
                case "pdf" -> extractPdf(bytes);
                case "docx" -> extractDocx(bytes);
                case "md", "markdown", "txt" -> new String(bytes, StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException(
                        "暂不支持的文件类型 ." + extension + "，当前支持 pdf / docx / md / txt");
            };
        } catch (IOException e) {
            throw new UncheckedIOException("解析文件失败: " + fileName, e);
        }
    }

    private String extractPdf(byte[] bytes) throws IOException {
        Document document = new ApachePdfBoxDocumentParser().parse(new ByteArrayInputStream(bytes));
        return document.text();
    }

    private String extractDocx(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder builder = new StringBuilder();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text != null && !text.isBlank()) {
                        builder.append(text.trim()).append('\n');
                    }
                } else if (element instanceof XWPFTable table) {
                    appendTable(builder, table);
                }
            }
            return builder.toString();
        }
    }

    /** 表格按行拍平，保留单元格之间的分隔符，避免检索时丢失结构信息。 */
    private void appendTable(StringBuilder builder, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText().replace('\n', ' ').trim());
            }
            builder.append(String.join(" | ", cells)).append('\n');
        }
        builder.append('\n');
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

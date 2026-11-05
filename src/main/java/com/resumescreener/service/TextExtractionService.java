package com.resumescreener.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Step 1 - ingest .pdf / .docx / .txt and return raw text. */
@Service
public class TextExtractionService {

    public record Extraction(String text, List<String> warnings) {
    }

    public static final int MIN_TEXT_LENGTH = 40;

    public Extraction extract(String fileName, byte[] data) throws IOException {
        String ext = extensionOf(fileName);
        List<String> warnings = new ArrayList<>();
        String text = switch (ext) {
            case "pdf" -> extractPdf(data);
            case "docx" -> extractDocx(data);
            case "txt", "md" -> new String(data, StandardCharsets.UTF_8);
            default -> throw new IOException("Unsupported file type '." + ext + "' (use PDF, DOCX or TXT)");
        };
        text = text.replace('\u0000', ' ').replace("\r\n", "\n").strip();
        if (text.length() < MIN_TEXT_LENGTH) {
            warnings.add(ext.equals("pdf")
                    ? "Almost no text found - this looks like a scanned/image PDF. OCR (Tesseract) is not enabled."
                    : "Almost no text could be extracted from this file.");
        }
        return new Extraction(text, warnings);
    }

    private static String extractPdf(byte[] data) throws IOException {
        try (PDDocument doc = Loader.loadPDF(data)) {
            if (doc.isEncrypted()) {
                throw new IOException("PDF is password protected");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    private static String extractDocx(byte[] data) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append('\n');
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText().strip());
                    }
                    sb.append(String.join(" | ", cells)).append('\n');
                }
            }
            return sb.toString();
        }
    }

    static String extensionOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}

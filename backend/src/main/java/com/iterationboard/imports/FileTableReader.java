package com.iterationboard.imports;

import com.iterationboard.common.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Excel/CSV 读取：xlsx 用 POI，csv 原生解析。 */
@Component
public class FileTableReader {
    public record Table(List<String> headers, List<List<String>> rows) {}

    public static final int MAX_ROWS = 500;

    public Table parse(byte[] bytes, String filename) {
        String name = filename == null ? "" : filename.toLowerCase();
        try {
            if (name.endsWith(".csv") || name.endsWith(".txt")) return parseCsv(bytes);
            return parseXlsx(bytes);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.validation("文件解析失败：" + e.getMessage());
        }
    }

    private Table parseXlsx(byte[] bytes) throws Exception {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) throw ApiException.validation("Excel 没有工作表");
            DataFormatter fmt = new DataFormatter();
            List<List<String>> all = new ArrayList<>();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                int last = Math.max(0, row.getLastCellNum());
                for (int c = 0; c < last; c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : fmt.formatCellValue(cell).strip());
                }
                if (cells.stream().allMatch(String::isBlank)) continue;
                all.add(cells);
            }
            return toTable(all);
        }
    }

    private Table parseCsv(byte[] bytes) throws Exception {
        List<List<String>> all = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                all.add(parseCsvLine(line));
            }
        }
        return toTable(all);
    }

    /** 简易 CSV 行解析：支持双引号包裹与引号内逗号/转义双引号。 */
    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else cur.append(c);
            } else if (c == '"') inQuotes = true;
            else if (c == ',') { out.add(cur.toString().strip()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString().strip());
        return out;
    }

    private Table toTable(List<List<String>> all) {
        if (all.isEmpty()) throw ApiException.validation("文件没有内容");
        List<String> headers = all.get(0);
        List<List<String>> rows = all.subList(1, all.size());
        if (rows.size() > MAX_ROWS) throw ApiException.validation("文件行数超过上限 " + MAX_ROWS);
        return new Table(headers, rows);
    }

    /** 表格转文本（粘贴导入的 Excel/CSV 路径：先由程序转文本再交给 AI 解析）。 */
    public String toText(Table t) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", t.headers())).append("\n");
        for (List<String> row : t.rows()) sb.append(String.join("\t", row)).append("\n");
        return sb.toString();
    }
}

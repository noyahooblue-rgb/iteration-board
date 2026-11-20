package com.iterationboard.report;

import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.repo.*;
import com.iterationboard.team.TeamService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Excel 导出：事项清单 .xlsx。
 * 列：编号/类型/标题/状态/优先级/负责人/验收人/迭代/截止日期/估算/进度/标签/创建时间。
 */
@Service
public class ExportService {
    private final ItemRepo itemRepo;
    private final ItemOwnerRepo ownerRepo;
    private final ItemLabelRepo itemLabelRepo;
    private final TeamService teamService;

    public ExportService(ItemRepo itemRepo, ItemOwnerRepo ownerRepo, ItemLabelRepo itemLabelRepo, TeamService teamService) {
        this.itemRepo = itemRepo;
        this.ownerRepo = ownerRepo;
        this.itemLabelRepo = itemLabelRepo;
        this.teamService = teamService;
    }

    static String typeLabel(ItemType t) {
        return switch (t) {
            case TASK -> "任务";
            case STORY -> "故事";
            case DEFECT -> "缺陷";
            case ANALYSIS -> "分析";
            case ADHOC -> "临时事项";
        };
    }

    static String statusLabel(ItemStatus s) {
        return switch (s) {
            case UNCLAIMED -> "待认领";
            case NOT_STARTED -> "待开始";
            case IN_PROGRESS -> "进行中";
            case IN_REVIEW -> "待验收";
            case DONE -> "已完成";
            case CANCELLED -> "已取消";
        };
    }

    // ---------- P20：报告导出 Word（.docx）与 HTML（复制为邮件正文） ----------

    /** 把报告 Markdown 转为结构化 HTML（标题/列表/表格/段落），供「复制为邮件正文」。 */
    public static String markdownToHtml(String md) {
        StringBuilder html = new StringBuilder();
        List<String> lines = (md == null ? "" : md).lines().toList();
        boolean inList = false;
        boolean inTable = false;
        for (int idx = 0; idx < lines.size(); idx++) {
            String line = lines.get(idx);
            String trimmed = line.trim();
            boolean isTableRow = trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 1;
            if (isTableRow) {
                if (!inTable) { html.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">\n"); inTable = true; }
                String[] cells = trimmed.substring(1, trimmed.length() - 1).split("\\|", -1);
                boolean separator = java.util.Arrays.stream(cells).allMatch(c -> c.trim().matches(":?-{3,}:?"));
                if (!separator) {
                    html.append("<tr>");
                    for (String c : cells) html.append("<td>").append(escapeHtml(c.trim())).append("</td>");
                    html.append("</tr>\n");
                }
                continue;
            } else if (inTable) {
                html.append("</table>\n");
                inTable = false;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                if (!inList) { html.append("<ul>\n"); inList = true; }
                html.append("<li>").append(escapeHtml(trimmed.substring(2))).append("</li>\n");
                continue;
            } else if (inList) {
                html.append("</ul>\n");
                inList = false;
            }
            if (trimmed.startsWith("### ")) html.append("<h3>").append(escapeHtml(trimmed.substring(4))).append("</h3>\n");
            else if (trimmed.startsWith("## ")) html.append("<h2>").append(escapeHtml(trimmed.substring(3))).append("</h2>\n");
            else if (trimmed.startsWith("# ")) html.append("<h1>").append(escapeHtml(trimmed.substring(2))).append("</h1>\n");
            else if (!trimmed.isBlank()) html.append("<p>").append(escapeHtml(trimmed)).append("</p>\n");
        }
        if (inList) html.append("</ul>\n");
        if (inTable) html.append("</table>\n");
        return html.toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 报告 Markdown → .docx（POI XWPF）：保留标题层级、无序列表与表格结构。 */
    public byte[] exportReportDocx(Report report) {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<String> lines = (report.getContent() == null ? "" : report.getContent()).lines().toList();
            int i = 0;
            while (i < lines.size()) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 1) {
                    List<String[]> rows = new ArrayList<>();
                    while (i < lines.size()) {
                        String t = lines.get(i).trim();
                        if (!(t.startsWith("|") && t.endsWith("|") && t.length() > 1)) break;
                        String[] cells = t.substring(1, t.length() - 1).split("\\|", -1);
                        boolean separator = java.util.Arrays.stream(cells).allMatch(c -> c.trim().matches(":?-{3,}:?"));
                        if (!separator) {
                            String[] trimmedCells = new String[cells.length];
                            for (int c = 0; c < cells.length; c++) trimmedCells[c] = cells[c].trim();
                            rows.add(trimmedCells);
                        }
                        i++;
                    }
                    if (!rows.isEmpty()) {
                        int cols = rows.stream().mapToInt(r -> r.length).max().orElse(1);
                        var table = doc.createTable(rows.size(), cols);
                        for (int r = 0; r < rows.size(); r++) {
                            for (int c = 0; c < cols; c++) {
                                table.getRow(r).getCell(c).setText(c < rows.get(r).length ? rows.get(r)[c] : "");
                            }
                        }
                    }
                    continue;
                }
                i++;
                if (trimmed.isBlank()) continue;
                var para = doc.createParagraph();
                var run = para.createRun();
                if (trimmed.startsWith("### ")) { run.setBold(true); run.setFontSize(13); run.setText(trimmed.substring(4)); }
                else if (trimmed.startsWith("## ")) { run.setBold(true); run.setFontSize(15); run.setText(trimmed.substring(3)); }
                else if (trimmed.startsWith("# ")) { run.setBold(true); run.setFontSize(18); run.setText(trimmed.substring(2)); }
                else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) { run.setText("• " + trimmed.substring(2)); }
                else run.setText(trimmed);
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Word 导出失败", e);
        }
    }

    @Transactional(readOnly = true)
    public byte[] exportItems(Long teamId, AppUser user, Long iterationId, ItemStatus status) {
        teamService.requireMember(teamId, user);
        List<Item> items = itemRepo.findByTeamId(teamId).stream()
                .filter(i -> i.getDeletedAt() == null)
                .filter(i -> iterationId == null || (i.getIteration() != null && i.getIteration().getId().equals(iterationId)))
                .filter(i -> status == null || i.getStatus() == status)
                .sorted(java.util.Comparator.comparingLong(Item::getSeq))
                .toList();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("事项清单");
            String[] headers = {"编号", "类型", "标题", "状态", "优先级", "负责人", "验收人", "迭代",
                    "截止日期", "估算", "进度%", "标签", "创建时间"};
            Row head = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) head.createCell(c).setCellValue(headers[c]);
            int r = 1;
            for (Item i : items) {
                Row row = sheet.createRow(r++);
                int c = 0;
                row.createCell(c++).setCellValue(i.getCode());
                row.createCell(c++).setCellValue(typeLabel(i.getType()));
                row.createCell(c++).setCellValue(i.getTitle());
                row.createCell(c++).setCellValue(statusLabel(i.getStatus()));
                row.createCell(c++).setCellValue(i.getPriority().name());
                row.createCell(c++).setCellValue(ownerRepo.findByItemId(i.getId()).stream()
                        .map(o -> o.getUser().getDisplayName()).collect(Collectors.joining("、")));
                row.createCell(c++).setCellValue(i.getAcceptor() == null ? "" : i.getAcceptor().getDisplayName());
                row.createCell(c++).setCellValue(i.getIteration() == null ? "" : i.getIteration().getName());
                row.createCell(c++).setCellValue(i.getDueDate() == null ? "" : i.getDueDate().toString());
                row.createCell(c++).setCellValue(i.getEstimate() == null ? "" : i.getEstimate().toPlainString());
                row.createCell(c++).setCellValue(i.getProgressPercent());
                row.createCell(c++).setCellValue(itemLabelRepo.findByItemId(i.getId()).stream()
                        .map(il -> il.getLabel().getName()).collect(Collectors.joining("、")));
                row.createCell(c).setCellValue(i.getCreatedAt() == null ? "" : i.getCreatedAt().toString());
            }
            for (int c = 0; c < headers.length; c++) sheet.autoSizeColumn(c);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Excel 导出失败", e);
        }
    }
}

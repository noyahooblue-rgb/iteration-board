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

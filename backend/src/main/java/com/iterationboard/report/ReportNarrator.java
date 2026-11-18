package com.iterationboard.report;

import com.iterationboard.domain.AppUser;

import java.util.Optional;

/**
 * 报告叙述生成 SPI（M4 自动汇总）：ai 模块实现。入参 facts 为程序算出的事实表（Markdown），
 * 实现方只能据此写叙述，且须自校验文中编号真实存在、数字与事实表一致；不可用/校验不过返回 empty，
 * 调用方回退程序模板（aiGenerated=false）。
 */
public interface ReportNarrator {
    Optional<String> narrate(Long teamId, AppUser actor, String kind, String factsMarkdown);
}

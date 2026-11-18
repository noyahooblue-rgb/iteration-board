package com.iterationboard.domain;

public final class Enums {
    private Enums() {}

    public enum Role { LEADER, PO, MEMBER }

    /** 修订一类型模型：TASK 任务 / STORY 故事 / DEFECT 缺陷 / ANALYSIS 分析 / ADHOC 临时事项。 */
    public enum ItemType { TASK, STORY, DEFECT, ANALYSIS, ADHOC }

    /** 故事类别（仅 STORY）。 */
    public enum StoryCategory { DEV, ANALYSIS, TEST, OTHER }

    public enum ItemStatus { UNCLAIMED, NOT_STARTED, IN_PROGRESS, IN_REVIEW, DONE, CANCELLED }

    /** 任务专用状态（由故事推算，非流转产生）。 */
    public enum TaskStatus { NOT_STARTED, IN_PROGRESS, PENDING_ACCEPTANCE, DONE, CANCELLED }

    public enum Priority { P0, P1, P2, P3 }
}

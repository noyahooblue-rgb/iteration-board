package com.iterationboard.config;

import com.iterationboard.domain.*;
import com.iterationboard.team.TeamSettings;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 对外 DTO（与 contract/openapi.yaml 的 schemas 对齐）。 */
public final class Dtos {
    private Dtos() {}

    public record UserDto(Long id, String loginName, String displayName, String avatarColor,
                          boolean platformAdmin, boolean mustChangePassword) {
        public static UserDto of(AppUser u) {
            return new UserDto(u.getId(), u.getLoginName(), u.getDisplayName(), u.getAvatarColor(),
                    u.isPlatformAdmin(), u.isMustChangePassword());
        }
    }

    public record TeamDto(Long id, String name, String code, String inviteCode, boolean joinApproval,
                          int iterationWeeks, String estimateUnit, String themeColor, String status, String myRole,
                          String description, JsonNode settings) {
        public static TeamDto of(Team t, Enums.Role myRole, boolean includeInviteCode) {
            return new TeamDto(t.getId(), t.getName(), t.getCode(), includeInviteCode ? t.getInviteCode() : null,
                    t.isJoinApproval(), t.getIterationWeeks(), t.getEstimateUnit(), t.getThemeColor(), t.getStatus(),
                    myRole == null ? null : myRole.name(),
                    t.getDescription(), TeamSettings.from(t).toJson());
        }
    }

    public record MemberDto(Long userId, String displayName, String loginName, String avatarColor,
                            String role, String memberStatus, LocalDate memberStatusStart, LocalDate memberStatusEnd,
                            boolean duplicateName) {
        public static MemberDto of(TeamMember m) {
            return of(m, false);
        }

        /** P04：同组重名时 duplicateName=true，前端据此在显示姓名后附带 loginName。 */
        public static MemberDto of(TeamMember m, boolean duplicateName) {
            AppUser u = m.getUser();
            return new MemberDto(u.getId(), u.getDisplayName(), u.getLoginName(), u.getAvatarColor(),
                    m.getRole().name(), m.getMemberStatus(), m.getStatusStart(), m.getStatusEnd(), duplicateName);
        }
    }

    public record JoinRequestDto(Long id, Long userId, String displayName, String loginName, String status, Instant createdAt) {
        public static JoinRequestDto of(JoinRequest r) {
            AppUser u = r.getUser();
            return new JoinRequestDto(r.getId(), u.getId(), u.getDisplayName(), u.getLoginName(), r.getStatus(), r.getCreatedAt());
        }
    }

    public record ItemDto(Long id, String code, String type, String title, String description, String status,
                          String priority, Long creatorId, List<Long> ownerIds, Long acceptorId,
                          List<Long> collaboratorIds, Long parentId, Long iterationId, LocalDate dueDate,
                          BigDecimal estimate, int progressPercent, boolean blocked, String blockReason,
                          int nudgeCount, List<String> labels, long childCount, long childDoneCount,
                          long commentCount, int version, Instant createdAt, Instant updatedAt, Instant lastProgressAt,
                          List<ChecklistDto> checklist,
                          String storyCategory, Long poId, Long batchId, boolean assignedDirectly,
                          int sortOrder, String taskStatus, List<Long> inviteeIds,
                          String source, String externalSystem, String externalId, String externalUrl,
                          boolean externalClosed, List<String> allowedActions, String acceptorNote,
                          boolean rejected, String rejectReason, Long teamId) {}

    public record BatchMemberDto(Long userId, Long itemId, String status) {}

    public record BatchSummaryDto(Long batchId, Long teamId, Long creatorId, String title, String type,
                                  int total, int doneCount, List<BatchMemberDto> members, Instant createdAt) {}

    public record BatchNudgeDto(List<Long> nudgedItemIds, List<Long> skippedItemIds, List<Long> redirectedItemIds) {}

    public record BacklogEntryDto(ItemDto task, List<ItemDto> stories, int storyCount, int doneStoryCount) {}

    public record SavedFilterDto(Long id, String name, tools.jackson.databind.JsonNode criteria, Instant createdAt) {}

    public record PlanningGroupDto(ItemDto task, List<ItemDto> items) {}

    public record PlanningLoadDto(Long userId, BigDecimal points, int itemCount) {}

    public record PlanningCapacityDto(Long userId, int availableDays, BigDecimal claimedPoints,
                                      int remainingDays, BigDecimal capacityPoints, boolean overCapacity) {}

    public record PlanningDto(IterationDto iteration, List<PlanningGroupDto> groups,
                              List<PlanningLoadDto> loads, List<PlanningCapacityDto> capacities) {}

    /** 检查清单条目。 */
    public record ChecklistDto(Long id, Long itemId, String text, boolean done, int sortOrder) {
        public static ChecklistDto of(ChecklistEntry e) {
            return new ChecklistDto(e.getId(), e.getItem().getId(), e.getText(), e.isDone(), e.getSortOrder());
        }
    }

    public record ProgressDto(Long id, Long itemId, Long authorId, String text, Integer percent, boolean blocked,
                              String blockReason, String nextStep, String kind, List<Long> mentionIds, Instant createdAt) {
        public static ProgressDto of(ProgressUpdate p) {
            return new ProgressDto(p.getId(), p.getItem().getId(), p.getAuthor().getId(), p.getText(), p.getPercent(),
                    p.isBlocked(), p.getBlockReason(), p.getNextStep(), p.getKind(),
                    p.getMentionIds() == null ? List.of() : List.copyOf(p.getMentionIds()), p.getCreatedAt());
        }
    }

    public record CommentDto(Long id, Long itemId, Long authorId, String content, List<Long> mentionIds, Instant createdAt) {
        public static CommentDto of(Comment c) {
            return new CommentDto(c.getId(), c.getItem().getId(), c.getAuthor().getId(), c.getContent(),
                    List.copyOf(c.getMentionIds()), c.getCreatedAt());
        }
    }

    public record ActivityDto(Long id, Long itemId, Long actorId, String action, String fromStatus, String toStatus,
                              String detail, Instant createdAt) {
        public static ActivityDto of(Activity a) {
            return new ActivityDto(a.getId(), a.getItem().getId(), a.getActor().getId(), a.getAction(),
                    a.getFromStatus(), a.getToStatus(), a.getDetail(), a.getCreatedAt());
        }
    }

    public record IterationDto(Long id, String name, String goal, LocalDate startDate, LocalDate endDate, String status) {
        public static IterationDto of(Iteration i) {
            return new IterationDto(i.getId(), i.getName(), i.getGoal(), i.getStartDate(), i.getEndDate(), i.getStatus());
        }
    }
}

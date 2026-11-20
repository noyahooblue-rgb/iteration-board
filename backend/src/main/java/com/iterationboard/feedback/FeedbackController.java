package com.iterationboard.feedback;

import com.iterationboard.admin.VersionInfo;
import com.iterationboard.common.ApiException;
import com.iterationboard.common.IdempotencyHelper;
import com.iterationboard.common.PlatformTime;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.*;
import com.iterationboard.item.NotifyService;
import com.iterationboard.repo.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 意见反馈（修订三第四节，平台级）：
 * - 所有登录用户：列表（状态/类型筛选，+1 数或时间排序）/ 查看 / 创建 / +1（防重复）/ 评论；
 * - 提交人：可改自己状态为 NEW（新提交）的反馈；
 * - 仅平台管理员：改状态（NEW→ANALYZING→ACCEPTED 填计划版本 / REJECTED 必填原因 →RELEASED 填上线版本）、
 *   官方回复、导出 zip（Markdown 汇总 + 图片）。状态变化通知提交人与 +1 的人（走 NotifyService）。
 * - 创建走幂等键（Idempotency-Key）；每人每天最多 20 条（平台时区口径）。
 * - 图片：最多 5 张、每张 ≤5MB、仅 image/* 类型（后端此前无贴图存储，内容 Base64 存库，见 FeedbackImage）。
 */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {
    static final List<String> TYPES = List.of("ISSUE", "SUGGESTION", "OTHER");
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final int DAILY_LIMIT = 20;

    private final FeedbackRepo feedbackRepo;
    private final FeedbackCommentRepo commentRepo;
    private final FeedbackPlusOneRepo plusOneRepo;
    private final FeedbackImageRepo imageRepo;
    private final CurrentUser currentUser;
    private final NotifyService notifyService;
    private final IdempotencyHelper idempotency;
    private final PlatformTime platformTime;
    private final VersionInfo versionInfo;

    public FeedbackController(FeedbackRepo feedbackRepo, FeedbackCommentRepo commentRepo,
                              FeedbackPlusOneRepo plusOneRepo, FeedbackImageRepo imageRepo,
                              CurrentUser currentUser, NotifyService notifyService,
                              IdempotencyHelper idempotency, PlatformTime platformTime, VersionInfo versionInfo) {
        this.feedbackRepo = feedbackRepo;
        this.commentRepo = commentRepo;
        this.plusOneRepo = plusOneRepo;
        this.imageRepo = imageRepo;
        this.currentUser = currentUser;
        this.notifyService = notifyService;
        this.idempotency = idempotency;
        this.platformTime = platformTime;
        this.versionInfo = versionInfo;
    }

    public record ImageInput(String fileName, String contentType, String contentBase64) {}
    public record CreateRequest(String type, String title, String description, String pageUrl,
                                String appVersion, String browserInfo, List<ImageInput> images) {}
    public record PatchRequest(String type, String title, String description, List<ImageInput> images) {}
    public record CommentRequest(String content) {}
    public record StatusRequest(String status, String plannedVersion, String rejectReason,
                                String releasedVersion, String officialReply) {}
    public record OfficialReplyRequest(String officialReply) {}
    public record ExportRequest(List<Long> ids) {}

    private Feedback requireFeedback(Long id) {
        return feedbackRepo.findById(id).orElseThrow(() -> ApiException.notFound("反馈不存在"));
    }

    private void requireAdmin(AppUser u) {
        if (!u.isPlatformAdmin()) throw ApiException.forbidden("仅平台管理员可执行此操作");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> toMap(Feedback f, AppUser viewer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("submitterId", f.getSubmitter().getId());
        m.put("submitterName", f.getSubmitter().getDisplayName());
        m.put("submitterLoginName", f.getSubmitter().getLoginName());
        m.put("type", f.getType());
        m.put("title", f.getTitle());
        m.put("description", f.getDescription());
        m.put("pageUrl", f.getPageUrl());
        m.put("appVersion", f.getAppVersion());
        m.put("browserInfo", f.getBrowserInfo());
        m.put("status", f.getStatus());
        m.put("plannedVersion", f.getPlannedVersion());
        m.put("rejectReason", f.getRejectReason());
        m.put("releasedVersion", f.getReleasedVersion());
        m.put("officialReply", f.getOfficialReply());
        m.put("plusOneCount", plusOneRepo.countByFeedbackId(f.getId()));
        m.put("myPlusOne", viewer != null && plusOneRepo.findByFeedbackIdAndUserId(f.getId(), viewer.getId()).isPresent());
        m.put("commentCount", commentRepo.countByFeedbackId(f.getId()));
        m.put("images", imageRepo.findByFeedbackId(f.getId()).stream().map(img -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id", img.getId());
            im.put("fileName", img.getFileName());
            im.put("contentType", img.getContentType());
            im.put("sizeBytes", img.getSizeBytes());
            return im;
        }).toList());
        m.put("createdAt", f.getCreatedAt());
        m.put("updatedAt", f.getUpdatedAt());
        return m;
    }

    private void validateBasics(String type, String title, String description) {
        if (type == null || !TYPES.contains(type)) throw ApiException.validation("type 须为 ISSUE / SUGGESTION / OTHER");
        if (title == null || title.isBlank() || title.length() > 200) throw ApiException.validation("标题不能为空且不超过 200 字");
        if (description == null || description.isBlank()) throw ApiException.validation("描述必填");
    }

    private void saveImages(Feedback f, List<ImageInput> images) {
        if (images == null) return;
        if (images.size() > 5) throw ApiException.validation("图片最多 5 张");
        for (ImageInput in : images) {
            if (in.contentType() == null || !in.contentType().startsWith("image/")) {
                throw ApiException.validation("只允许上传图片文件");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(in.contentBase64() == null ? "" : in.contentBase64());
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("图片内容不是合法的 Base64");
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw ApiException.validation("每张图片不超过 5MB");
            }
            FeedbackImage img = new FeedbackImage();
            img.setFeedback(f);
            img.setFileName(in.fileName() == null || in.fileName().isBlank() ? "image" : in.fileName());
            img.setContentType(in.contentType());
            img.setSizeBytes(bytes.length);
            img.setContentBase64(in.contentBase64());
            imageRepo.save(img);
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest req,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        AppUser me = currentUser.require();
        String scope = "POST /feedback";
        if (idemKey != null) {
            var replay = idempotency.replay(me.getId(), scope, idemKey);
            if (replay.isPresent()) return replay.get();
        }
        Map<String, Object> body = doCreate(me, req);
        if (idemKey != null) return idempotency.remember(me.getId(), scope, idemKey, HttpStatus.CREATED.value(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Transactional
    public Map<String, Object> doCreate(AppUser me, CreateRequest req) {
        validateBasics(req.type(), req.title(), req.description());
        long todayCount = feedbackRepo.countBySubmitterIdAndCreatedAtGreaterThanEqual(
                me.getId(), platformTime.startOfTodayInstant());
        if (todayCount >= DAILY_LIMIT) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "FEEDBACK_DAILY_LIMIT",
                    "每人每天最多提交 20 条反馈，明天再来");
        }
        Feedback f = new Feedback();
        f.setSubmitter(me);
        f.setType(req.type());
        f.setTitle(req.title());
        f.setDescription(req.description());
        f.setPageUrl(req.pageUrl());
        f.setAppVersion(req.appVersion() == null ? versionInfo.version() : req.appVersion());
        f.setBrowserInfo(req.browserInfo());
        feedbackRepo.save(f);
        saveImages(f, req.images());
        return toMap(f, me);
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String sort) {
        AppUser me = currentUser.require();
        List<Feedback> all = feedbackRepo.findAllByOrderByCreatedAtDesc().stream()
                .filter(f -> status == null || status.equals(f.getStatus()))
                .filter(f -> type == null || type.equals(f.getType()))
                .toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Feedback f : all) out.add(toMap(f, me));
        if ("PLUS_ONES".equals(sort)) {
            out.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("plusOneCount")).reversed());
        }
        return out;
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> get(@PathVariable Long id) {
        AppUser me = currentUser.require();
        return toMap(requireFeedback(id), me);
    }

    @PatchMapping("/{id}")
    @Transactional
    public Map<String, Object> patch(@PathVariable Long id, @RequestBody PatchRequest req) {
        AppUser me = currentUser.require();
        Feedback f = requireFeedback(id);
        if (!f.getSubmitter().getId().equals(me.getId())) throw ApiException.forbidden("只能修改自己提交的反馈");
        if (!"NEW".equals(f.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "FEEDBACK_LOCKED", "反馈已开始处理，不能再修改");
        }
        String type = req.type() == null ? f.getType() : req.type();
        String title = req.title() == null ? f.getTitle() : req.title();
        String description = req.description() == null ? f.getDescription() : req.description();
        validateBasics(type, title, description);
        f.setType(type);
        f.setTitle(title);
        f.setDescription(description);
        feedbackRepo.save(f);
        if (req.images() != null) {
            imageRepo.deleteByFeedbackId(id);
            imageRepo.flush();
            saveImages(f, req.images());
        }
        return toMap(f, me);
    }

    @PostMapping("/{id}/plus-one")
    @Transactional
    public Map<String, Object> plusOne(@PathVariable Long id) {
        AppUser me = currentUser.require();
        Feedback f = requireFeedback(id);
        // 防重复：已 +1 时幂等返回当前状态，不重复计数
        if (plusOneRepo.findByFeedbackIdAndUserId(id, me.getId()).isEmpty()) {
            plusOneRepo.save(new FeedbackPlusOne(f, me));
        }
        return toMap(f, me);
    }

    @DeleteMapping("/{id}/plus-one")
    @Transactional
    public Map<String, Object> cancelPlusOne(@PathVariable Long id) {
        AppUser me = currentUser.require();
        Feedback f = requireFeedback(id);
        plusOneRepo.deleteByFeedbackIdAndUserId(id, me.getId());
        return toMap(f, me);
    }

    @GetMapping("/{id}/comments")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> comments(@PathVariable Long id) {
        currentUser.require();
        requireFeedback(id);
        return commentRepo.findByFeedbackIdOrderByCreatedAtAsc(id).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("feedbackId", id);
            m.put("authorId", c.getAuthor().getId());
            m.put("authorName", c.getAuthor().getDisplayName());
            m.put("content", c.getContent());
            m.put("createdAt", c.getCreatedAt());
            return m;
        }).toList();
    }

    @PostMapping("/{id}/comments")
    @Transactional
    public ResponseEntity<Map<String, Object>> addComment(@PathVariable Long id, @RequestBody CommentRequest req) {
        AppUser me = currentUser.require();
        Feedback f = requireFeedback(id);
        if (req.content() == null || req.content().isBlank()) throw ApiException.validation("评论内容不能为空");
        FeedbackComment c = new FeedbackComment();
        c.setFeedback(f);
        c.setAuthor(me);
        c.setContent(req.content());
        commentRepo.save(c);
        if (!f.getSubmitter().getId().equals(me.getId())) {
            notifyService.notifyInTeam(f.getSubmitter(), "FEEDBACK_COMMENT", null, null, me,
                    "你的反馈「" + f.getTitle() + "」有新评论");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("feedbackId", id);
        m.put("authorId", me.getId());
        m.put("authorName", me.getDisplayName());
        m.put("content", c.getContent());
        m.put("createdAt", c.getCreatedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(m);
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public Map<String, Object> changeStatus(@PathVariable Long id, @RequestBody StatusRequest req) {
        AppUser me = currentUser.require();
        requireAdmin(me);
        Feedback f = requireFeedback(id);
        String target = req.status();
        if (target == null) throw ApiException.validation("status 必填");
        String from = f.getStatus();
        boolean allowed = switch (from + "->" + target) {
            case "NEW->ANALYZING", "ANALYZING->ACCEPTED", "ANALYZING->REJECTED", "ACCEPTED->RELEASED" -> true;
            default -> false;
        };
        if (!allowed) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION",
                    "反馈状态不能从 " + from + " 直接变为 " + target + "（流程：新提交→分析中→已采纳/不采纳→已上线）");
        }
        if ("ACCEPTED".equals(target) && (req.plannedVersion() == null || req.plannedVersion().isBlank())) {
            throw ApiException.validation("已采纳必须填写计划版本 plannedVersion");
        }
        if ("REJECTED".equals(target) && (req.rejectReason() == null || req.rejectReason().isBlank())) {
            throw ApiException.validation("不采纳必须填写原因 rejectReason");
        }
        if ("RELEASED".equals(target) && (req.releasedVersion() == null || req.releasedVersion().isBlank())) {
            throw ApiException.validation("已上线必须填写上线版本 releasedVersion");
        }
        f.setStatus(target);
        if (req.plannedVersion() != null) f.setPlannedVersion(req.plannedVersion());
        if (req.rejectReason() != null) f.setRejectReason(req.rejectReason());
        if (req.releasedVersion() != null) f.setReleasedVersion(req.releasedVersion());
        if (req.officialReply() != null) f.setOfficialReply(req.officialReply());
        feedbackRepo.save(f);
        // 状态变化通知提交人与 +1 的人
        java.util.Set<Long> notified = new java.util.HashSet<>();
        String content = "反馈「" + f.getTitle() + "」状态变更：" + from + " → " + target;
        if (notified.add(f.getSubmitter().getId())) {
            notifyService.notifyInTeam(f.getSubmitter(), "FEEDBACK_STATUS", null, null, me, content);
        }
        for (FeedbackPlusOne p : plusOneRepo.findByFeedbackId(id)) {
            if (notified.add(p.getUser().getId())) {
                notifyService.notifyInTeam(p.getUser(), "FEEDBACK_STATUS", null, null, me, content);
            }
        }
        return toMap(f, me);
    }

    @PatchMapping("/{id}/official-reply")
    @Transactional
    public Map<String, Object> officialReply(@PathVariable Long id, @RequestBody OfficialReplyRequest req) {
        AppUser me = currentUser.require();
        requireAdmin(me);
        Feedback f = requireFeedback(id);
        if (req.officialReply() == null || req.officialReply().isBlank()) throw ApiException.validation("官方回复不能为空");
        f.setOfficialReply(req.officialReply());
        feedbackRepo.save(f);
        notifyService.notifyInTeam(f.getSubmitter(), "FEEDBACK_STATUS", null, null, me,
                "你的反馈「" + f.getTitle() + "」有官方回复");
        return toMap(f, me);
    }

    /** 管理员导出 zip：summary.md（Markdown 汇总）+ images/<feedbackId>/<fileName>。 */
    @PostMapping("/export")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> export(@RequestBody ExportRequest req) {
        AppUser me = currentUser.require();
        requireAdmin(me);
        if (req.ids() == null || req.ids().isEmpty()) throw ApiException.validation("请选择要导出的反馈");
        List<Feedback> selected = req.ids().stream().map(this::requireFeedback).toList();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            StringBuilder md = new StringBuilder("# 意见反馈导出\n\n");
            for (Feedback f : selected) {
                md.append("## ").append(f.getTitle()).append("\n\n");
                md.append("- 编号：").append(f.getId()).append("\n");
                md.append("- 类型：").append(f.getType()).append("\n");
                md.append("- 状态：").append(f.getStatus()).append("\n");
                md.append("- 提交人：").append(f.getSubmitter().getDisplayName())
                        .append("（").append(f.getSubmitter().getLoginName()).append("）\n");
                md.append("- +1 数：").append(plusOneRepo.countByFeedbackId(f.getId())).append("\n");
                if (f.getPageUrl() != null) md.append("- 页面：").append(f.getPageUrl()).append("\n");
                if (f.getPlannedVersion() != null) md.append("- 计划版本：").append(f.getPlannedVersion()).append("\n");
                if (f.getRejectReason() != null) md.append("- 不采纳原因：").append(f.getRejectReason()).append("\n");
                if (f.getReleasedVersion() != null) md.append("- 上线版本：").append(f.getReleasedVersion()).append("\n");
                md.append("\n").append(f.getDescription()).append("\n\n");
                if (f.getOfficialReply() != null) md.append("官方回复：").append(f.getOfficialReply()).append("\n\n");
                for (FeedbackComment c : commentRepo.findByFeedbackIdOrderByCreatedAtAsc(f.getId())) {
                    md.append("> ").append(c.getAuthor().getDisplayName()).append("：").append(c.getContent()).append("\n\n");
                }
            }
            zip.putNextEntry(new ZipEntry("summary.md"));
            zip.write(md.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Feedback f : selected) {
                for (FeedbackImage img : imageRepo.findByFeedbackId(f.getId())) {
                    zip.putNextEntry(new ZipEntry("images/" + f.getId() + "/" + img.getFileName()));
                    zip.write(Base64.getDecoder().decode(img.getContentBase64()));
                    zip.closeEntry();
                }
            }
            zip.finish();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"feedback-export.zip\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(baos.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("反馈导出失败", e);
        }
    }
}

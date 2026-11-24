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
        // rc3（契约 3）：图片仅提交人与平台管理员可见，列表/详情据此渲染缩略图或提示行
        m.put("imagesVisible", viewer != null
                && (viewer.isPlatformAdmin() || f.getSubmitter().getId().equals(viewer.getId())));
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

    /**
     * rc3（契约 3）：不信任声明类型，读文件头魔数判定真实格式。
     * PNG 89 50 4E 47 0D 0A 1A 0A / JPEG FF D8 FF / GIF 47 49 46 38 /
     * WEBP 52 49 46 46 ?? ?? ?? ?? 57 45 42 50。判不出（含 SVG/HTML）返回 null。
     */
    static String detectImageType(byte[] b) {
        if (b == null) return null;
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return "image/png";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 4 && b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38) return "image/gif";
        if (b.length >= 12 && b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
                && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) return "image/webp";
        return null;
    }

    private void saveImages(Feedback f, List<ImageInput> images) {
        if (images == null) return;
        if (images.size() > 5) throw ApiException.validation("图片最多 5 张");
        for (ImageInput in : images) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(in.contentBase64() == null ? "" : in.contentBase64());
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("图片内容不是合法的 Base64");
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw ApiException.validation("每张图片不超过 5MB");
            }
            String detected = detectImageType(bytes);
            if (detected == null || !detected.equals(in.contentType())) {
                throw ApiException.validation("只支持 PNG / JPEG / WEBP / GIF 图片");
            }
            FeedbackImage img = new FeedbackImage();
            img.setFeedback(f);
            img.setFileName(in.fileName() == null || in.fileName().isBlank() ? "image" : in.fileName());
            img.setContentType(detected);
            img.setSizeBytes(bytes.length);
            img.setContentBase64(in.contentBase64());
            imageRepo.save(img);
        }
    }

    /**
     * rc4（D2）+ C2 复核处置（后端 4.2）：旧数据 Base64 容错解码，口径三步——
     * ① 剥掉可能的 "data:...;base64," 前缀并去掉全部空白（容忍旧版本/外部导入的 CRLF 折行形态）；
     * ② 用严格解码器解码（不用 MIME 解码器：它会静默跳过非法字符，真损坏的数据也可能被
     *    「解码成功」成一团不是图片的字节，再配上库里的声明类型以 200 返回一张假图）；
     * ③ 解出的字节按魔数校验确为 PNG/JPEG/GIF/WEBP（与 saveImages 的检测同口径）。
     * 任一步不过 → 业务错误 IMAGE_DATA_INVALID（422），不 500、不返回假图。
     * 新上传仍走 saveImages 的严格口径，本 helper 只用于读取侧（getImage / export）。
     */
    static byte[] decodeStoredImageBase64(String stored) {
        if (stored == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IMAGE_DATA_INVALID", "图片数据损坏");
        }
        String s = stored.trim();
        if (s.startsWith("data:")) {
            int comma = s.indexOf(',');
            if (comma >= 0) s = s.substring(comma + 1);
        }
        String compact = s.replaceAll("\\s+", "");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IMAGE_DATA_INVALID", "图片数据损坏");
        }
        if (detectImageType(bytes) == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IMAGE_DATA_INVALID", "图片数据损坏");
        }
        return bytes;
    }

    /**
     * rc3（契约 3）：读取反馈图片。仅提交人与平台管理员可见（截图可能含敏感信息）；
     * inline + nosniff + private 缓存头。未登录由安全层 401。
     */
    @GetMapping("/{id}/images/{imageId}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> getImage(@PathVariable Long id, @PathVariable Long imageId) {
        AppUser me = currentUser.require();
        Feedback f = requireFeedback(id);
        FeedbackImage img = imageRepo.findById(imageId)
                .filter(i -> i.getFeedback().getId().equals(id))
                .orElseThrow(() -> ApiException.notFound("图片不存在"));
        if (!me.isPlatformAdmin() && !f.getSubmitter().getId().equals(me.getId())) {
            throw ApiException.forbidden("图片仅提交人和管理员可见");
        }
        byte[] bytes = decodeStoredImageBase64(img.getContentBase64());
        // C2 复核处置（后端 4.2）：响应类型以字节魔数实测为准，不信库中声明——
        // decodeStoredImageBase64 已保证魔数可判，旧数据的声明类型即使有误也不会误导浏览器。
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(detectImageType(bytes)))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(bytes);
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
                    "你的反馈「" + f.getTitle() + "」有新评论", false, f.getId());
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
            notifyService.notifyInTeam(f.getSubmitter(), "FEEDBACK_STATUS", null, null, me, content, false, f.getId());
        }
        for (FeedbackPlusOne p : plusOneRepo.findByFeedbackId(id)) {
            if (notified.add(p.getUser().getId())) {
                notifyService.notifyInTeam(p.getUser(), "FEEDBACK_STATUS", null, null, me, content, false, f.getId());
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
                "你的反馈「" + f.getTitle() + "」有官方回复", false, f.getId());
        return toMap(f, me);
    }

    /**
     * 全局搜索的反馈部分（P2c 裁定举一反三项 2）：只搜本人提交的反馈（标题/描述含关键词），
     * 不含他人反馈；事项仍走团队搜索 /teams/{id}/search。
     */
    @GetMapping("/search")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(@RequestParam(required = false) String q) {
        AppUser me = currentUser.require();
        String kw = q == null ? "" : q.trim().toLowerCase();
        if (kw.isEmpty()) return List.of();
        return feedbackRepo.findBySubmitterIdOrderByCreatedAtDesc(me.getId()).stream()
                .filter(f -> f.getTitle().toLowerCase().contains(kw)
                        || f.getDescription().toLowerCase().contains(kw))
                .limit(20)
                .map(f -> toMap(f, me))
                .toList();
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
                    zip.write(decodeStoredImageBase64(img.getContentBase64()));
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

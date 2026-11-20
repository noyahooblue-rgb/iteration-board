package com.iterationboard.admin;

import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.AppUser;
import com.iterationboard.repo.UserRepo;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 更新说明（修订三二.4）：内置当前版本（1.1.0）中文更新说明；
 * GET 返回说明 + 当前用户是否已读（已读标记存 preferences_json 的 releaseNotesReadVersion），
 * POST /read 标记已读。系统信息页再次查看走同一 GET 接口。
 */
@RestController
@RequestMapping("/api/v1")
public class ReleaseNotesController {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String CONTENT = """
            ## 1.1.0 主要变化

            - 平台管理员账号改为安装时生成（登录名 admin，首次登录强制改密），注册账号不再自动成为管理员；平台管理新增授予/撤销管理员、停用账号与系统信息页。
            - 成员离组交接：移出、退出或停用账号前，必须先处理其未完成事项、待验收事项与发出的每人一份批次，可逐条转交或一键退回认领池。
            - 新增意见反馈：所有登录用户可提交（可附截图）、+1 与评论；平台管理员可流转状态、官方回复并导出。
            - 验收规则调整：组长和 PO 都可验收故事与任务；小组可设置故事/任务默认验收角色；不能验收自己负责的事项，系统会自动转交另一角色。
            - 拆故事放宽：组长和 PO 都可创建故事与使用 AI 拆故事。
            - 未开始迭代的事项不再产生未动工/未更新提醒与风险信号；独立事项计入个人负载与总览、不计入燃尽图，迭代总结单列「迭代外工作」。
            - 每人一份默认不含发布人（可勾选包含）；被催点「卡住了」须填写一句话原因。
            - 结束迭代新增确认数据与 24 小时内撤销结束；请假设置可查看请假期间到期事项，请假前一个工作日自动提醒本人与组长。
            - 报告可导出 Word 文档与 HTML 邮件正文；站会摘要显示「数据截至」时间，组长/PO 可刷新。
            - 通知新增精简模式与 5 分钟合并；同组重名成员显示时附带登录名；备份状态可在平台管理查看，超 3 天未备份会提示管理员。
            """;

    private final CurrentUser currentUser;
    private final UserRepo userRepo;
    private final VersionInfo versionInfo;

    public ReleaseNotesController(CurrentUser currentUser, UserRepo userRepo, VersionInfo versionInfo) {
        this.currentUser = currentUser;
        this.userRepo = userRepo;
        this.versionInfo = versionInfo;
    }

    private boolean readBy(AppUser u) {
        String json = u.getPreferencesJson();
        if (json == null || json.isBlank()) return false;
        try {
            return versionInfo.version().equals(MAPPER.readTree(json).path("releaseNotesReadVersion").asText(null));
        } catch (Exception e) {
            return false;
        }
    }

    @GetMapping("/release-notes")
    @Transactional(readOnly = true)
    public Map<String, Object> releaseNotes() {
        AppUser me = currentUser.require();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", versionInfo.version());
        out.put("title", "本次更新了什么（" + versionInfo.version() + "）");
        out.put("content", CONTENT);
        out.put("read", readBy(me));
        return out;
    }

    @PostMapping("/release-notes/read")
    @Transactional
    public Map<String, Object> markRead() {
        AppUser me = currentUser.require();
        try {
            ObjectNode root = (me.getPreferencesJson() == null || me.getPreferencesJson().isBlank())
                    ? MAPPER.createObjectNode()
                    : (ObjectNode) MAPPER.readTree(me.getPreferencesJson());
            root.put("releaseNotesReadVersion", versionInfo.version());
            me.setPreferencesJson(MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            throw new IllegalStateException("preferences_json 处理失败", e);
        }
        userRepo.save(me);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", versionInfo.version());
        out.put("read", true);
        return out;
    }
}

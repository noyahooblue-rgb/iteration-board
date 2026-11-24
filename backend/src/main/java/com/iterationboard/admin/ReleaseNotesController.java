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
            ## 新功能

            - 点通知能直达事项了：在通知里点一条事项提醒，会直接打开对应事项，不用再自己去列表里找。
            - 新增意见反馈：所有登录用户都能提交（可以附截图）、+1 和评论；平台管理员可以流转处理状态、官方回复并导出。反馈里贴的图片现在能正常打开看、也能下载下来。
            - 管理员可以帮成员重置密码：成员忘了密码时，管理员在平台管理里重置，系统会生成一个临时密码（只显示一次），成员用它登录后会被要求改成自己的新密码。
            - 深色主题上线：在个人设置里可以切换。默认的深色是「暮光蓝灰」，也可以选「石墨」，或者跟随系统自动切换。
            - 换了新图标：蓝渐变底、白色无限环、右上角一颗金色星芒，浏览器标签页上的小图标也会跟着变。
            - 平台管理新增系统信息页：能看到当前版本、数据库、备份情况和局域网访问地址。这个「版本更新说明」弹窗会在你第一次登录新版时自动弹一次，看过就不再打扰，在系统信息页还能再打开看。
            - 成员离开小组前要先交接：没做完的事项、待验收的事项先转给别人或退回认领池，交接完成才能离组或停用账号。
            - 浏览器提醒更及时：标签页标题和图标上会显示未读数，也可以打开桌面通知和提示音。

            ## 改进

            - 验收更灵活：组长和 PO 都能验收故事与任务，小组还能设置默认由谁验收；不能验收自己负责的事项，系统会自动转给另一位。
            - 拆故事放宽：组长和 PO 都能创建故事、用 AI 拆故事。
            - 提醒更准：还没开始的迭代里的事项不再误报「未动工 / 未更新」；独立事项会计入个人工作量与总览，但不计入燃尽图，迭代总结里单列「迭代外工作」。
            - 每人一份默认不含发布人（可以勾选包含）；被催办点「卡住了」时要填一句话原因。
            - 结束迭代前会先让你确认影响，结束错了 24 小时内还能撤销结束；请假设置里能看到请假期间到期的事项，请假前一个工作日会自动提醒本人和组长。
            - 报告可以导出 Word 文档，也可以复制成 HTML 邮件正文发出去；站会摘要标注了「数据截至」时间，组长 / PO 可以手动刷新。
            - 通知可以开「精简模式」，5 分钟内的多条通知会合并；同组有重名成员时，显示时会带上登录名帮你区分。

            ## 修复

            - 修了反馈图片打不开、下载失败的问题：早期版本上传的图片（包括存得有点不规范的老图片）现在也能正常看、正常下载。
            - 修了点通知打开事项却提示「事项不存在」的问题。
            - 修了补丁脚本在部分电脑上双击没反应、闪退的问题，升级打补丁更稳了。
            - 修了备份状态不提醒的问题：超过 3 天没备份，平台管理里会提示管理员。

            ## 安全

            - 默认管理员更稳妥：安装后系统自动生成管理员账号 admin，初始密码写在安装目录的密码文件里；注册新账号不会再自动成为管理员，管理员只能由现有管理员在平台管理里授予或撤销。
            - 密码规则加强：设置新密码必须同时包含大写字母、小写字母、数字和特殊字符，太简单的密码设不了。
            - 第一次登录（或被管理员重置密码后）会进入专门的改密码页面，改完才能正常使用；改完后，装初始密码的文件会自动删掉。
            - 关掉了几个不需要登录就能调用的管理员接口，堵住了局域网内他人冒用管理员权限的风险。
            - 已删除事项的内容不会再被无权查看的人从链接里看到。
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

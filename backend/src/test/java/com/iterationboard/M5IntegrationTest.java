package com.iterationboard;

import com.iterationboard.domain.*;
import com.iterationboard.repo.*;
import com.iterationboard.retention.RetentionPurgeService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M5 集成测试：Q6 审计日志读取（权限/筛选/分页）+ Q4 留存物理清理（回收站 30 天等）。
 * 数据全为虚构演示数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class M5IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepo userRepo;
    @Autowired TeamRepo teamRepo;
    @Autowired TeamMemberRepo memberRepo;
    @Autowired AuditLogRepo auditLogRepo;
    @Autowired ItemRepo itemRepo;
    @Autowired ReportRepo reportRepo;
    @Autowired ReportVersionRepo reportVersionRepo;
    @Autowired NotificationRepo notificationRepo;
    @Autowired ChecklistRepo checklistRepo;
    @Autowired CommentRepo commentRepo;
    @Autowired RetentionPurgeService purgeService;
    @Autowired JdbcTemplate jdbc;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;

    // ---------- helpers ----------

    private String captchaCode(MockHttpSession session) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/auth/captcha").session(session))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        String captchaId = JsonPath.read(body, "$.captchaId");
        String svg = JsonPath.read(body, "$.imageSvg");
        Matcher m = Pattern.compile("<text[^>]*>(.)</text>").matcher(svg);
        StringBuilder code = new StringBuilder();
        while (m.find()) code.append(m.group(1));
        return captchaId + "|" + code;
    }

    private MockHttpSession register(String loginName, String displayName) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String[] c = captchaCode(session).split("\\|");
        mvc.perform(post("/api/v1/auth/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + loginName + "\",\"displayName\":\"" + displayName
                                + "\",\"password\":\"passw0rd1\",\"captchaId\":\"" + c[0]
                                + "\",\"captchaCode\":\"" + c[1] + "\"}"))
                .andExpect(status().isCreated());
        return session;
    }

    private long myId(MockHttpSession session) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long createTeam(MockHttpSession session, String name, String code) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/teams").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"joinApproval\":false}"))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void addMember(long teamId, long userId, Enums.Role role) {
        TeamMember m = new TeamMember();
        m.setTeam(teamRepo.findById(teamId).orElseThrow());
        m.setUser(userRepo.findById(userId).orElseThrow());
        m.setRole(role);
        memberRepo.save(m);
    }

    private AuditLog audit(long teamId, AppUser actor, String action) {
        return auditLogRepo.save(new AuditLog(teamId, actor, action, "ITEM", 42L, "演示审计 " + action));
    }

    // ---------- Q6：审计日志读取 ----------

    @Test
    void auditLogs_permissionsAndFilters() throws Exception {
        MockHttpSession adminS = register("m5admin", "平台管理员");
        long adminId = myId(adminS);
        AppUser admin = userRepo.findById(adminId).orElseThrow();
        assertThat(admin.isPlatformAdmin()).isTrue();
        long teamA = createTeam(adminS, "审计甲组", "SJA");
        long teamB = createTeam(adminS, "审计乙组", "SJB");

        MockHttpSession leaderS = register("m5leader", "组长甲");
        long leaderId = myId(leaderS);
        addMember(teamA, leaderId, Enums.Role.LEADER);
        addMember(teamB, leaderId, Enums.Role.MEMBER); // 乙组仅普通成员身份

        MockHttpSession memberS = register("m5member", "成员甲");
        long memberId = myId(memberS);
        addMember(teamA, memberId, Enums.Role.MEMBER);

        MockHttpSession poS = register("m5po", "PO甲");
        addMember(teamA, myId(poS), Enums.Role.PO);

        AppUser leader = userRepo.findById(leaderId).orElseThrow();
        audit(teamA, admin, "ITEM_CREATED");
        audit(teamA, leader, "ITEM_DELETED");
        audit(teamB, admin, "ITEM_CREATED");

        // 组长读本组 200，字段齐全且含 actorName
        mvc.perform(get("/api/v1/teams/" + teamA + "/audit-logs").session(leaderS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].action").value("ITEM_DELETED"))
                .andExpect(jsonPath("$.content[0].actorName").value("组长甲"))
                .andExpect(jsonPath("$.content[0].teamId").value((int) teamA))
                .andExpect(jsonPath("$.content[0].targetType").value("ITEM"));
        // 普通成员 403、PO 403
        mvc.perform(get("/api/v1/teams/" + teamA + "/audit-logs").session(memberS))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/teams/" + teamA + "/audit-logs").session(poS))
                .andExpect(status().isForbidden());
        // 跨组：leader 在乙组只是普通成员 → 403；且对甲组日志不可见于乙组
        mvc.perform(get("/api/v1/teams/" + teamB + "/audit-logs").session(leaderS))
                .andExpect(status().isForbidden());
        // 平台管理员读小组 200；管理端读全部 200（3 条），按 teamId 筛选生效
        mvc.perform(get("/api/v1/teams/" + teamA + "/audit-logs").session(adminS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/admin/audit-logs").session(adminS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/v1/admin/audit-logs").session(adminS).param("teamId", String.valueOf(teamB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        // 非管理员访问管理端 403
        mvc.perform(get("/api/v1/admin/audit-logs").session(leaderS))
                .andExpect(status().isForbidden());
        // 筛选：actorId / action / 分页
        mvc.perform(get("/api/v1/teams/" + teamA + "/audit-logs").session(leaderS)
                        .param("actorId", String.valueOf(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("ITEM_CREATED"));
        mvc.perform(get("/api/v1/teams/" + teamA + "/audit-logs").session(leaderS)
                        .param("action", "ITEM_DELETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/teams/" + teamA + "/audit-logs").session(leaderS)
                        .param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
        // 筛选：from/to（把一条回拨到 10 天前，窗口外）
        AuditLog old = audit(teamA, admin, "OLD_ACTION");
        jdbc.update("UPDATE audit_log SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)), old.getId());
        String from = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        mvc.perform(get("/api/v1/teams/" + teamA + "/audit-logs").session(leaderS).param("from", from))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        String to = Instant.now().minus(5, ChronoUnit.DAYS).toString();
        mvc.perform(get("/api/v1/teams/" + teamA + "/audit-logs").session(leaderS).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("OLD_ACTION"));
    }

    // ---------- Q4：留存物理清理 ----------

    private Item item(Team team, AppUser creator, long seq, String title, Item parent, Instant deletedAt) {
        Item i = new Item();
        i.setTeam(team);
        i.setSeq(seq);
        i.setCode("M5-" + seq);
        i.setType(Enums.ItemType.TASK);
        i.setTitle(title);
        i.setStatus(Enums.ItemStatus.NOT_STARTED);
        i.setCreator(creator);
        i.setParent(parent);
        i.setDeletedAt(deletedAt);
        return itemRepo.save(i);
    }

    private int rowCount(String table, Long id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
    }

    private Report report(Team team, String title, Instant deletedAt) {
        Report r = new Report();
        r.setTeam(team);
        r.setKind("WEEKLY");
        r.setTitle(title);
        r.setContent("演示报告正文");
        r.setDeletedAt(deletedAt);
        return reportRepo.save(r);
    }

    @Test
    void retentionPurge_expiredOnly() {
        AppUser user = new AppUser();
        user.setLoginName("m5purge");
        user.setDisplayName("清理演示用户");
        user.setPasswordHash("x");
        user = userRepo.save(user);
        Team team = new Team();
        team.setName("清理演示组");
        team.setCode("QLYS");
        team.setInviteCode("M5PURGE1");
        team = teamRepo.save(team);

        Instant now = Instant.now();
        Instant expired = now.minus(31, ChronoUnit.DAYS);
        Instant fresh = now.minus(1, ChronoUnit.DAYS);

        // 事项：过期已删（带检查清单+评论）/ 未过期已删 / 未删除 / 过期父项+未删子项（跳过）/ 过期父子（同删）
        Item expiredItem = item(team, user, 1, "过期事项", null, expired);
        ChecklistEntry ce = new ChecklistEntry();
        ce.setItem(expiredItem); ce.setText("演示清单"); ce.setSortOrder(0);
        checklistRepo.save(ce);
        Comment cm = new Comment();
        cm.setItem(expiredItem); cm.setAuthor(user); cm.setContent("演示评论");
        commentRepo.save(cm);
        Item freshItem = item(team, user, 2, "未过期事项", null, fresh);
        Item liveItem = item(team, user, 3, "未删除事项", null, null);
        Item blockedParent = item(team, user, 4, "被子项挡住的父项", null, expired);
        Item liveChild = item(team, user, 5, "未删除子项", blockedParent, null);
        Item expiredParent = item(team, user, 6, "过期父项", null, expired);
        Item expiredChild = item(team, user, 7, "过期子项", expiredParent, expired);

        // 报告：过期已删（带版本快照）/ 未过期已删 / 未删除
        Report expiredReport = report(team, "过期报告", expired);
        reportVersionRepo.save(new ReportVersion(expiredReport));
        Report freshReport = report(team, "未过期报告", fresh);
        Report liveReport = report(team, "未删除报告", null);

        // 通知：91 天前 / 1 天前
        Notification oldN = notificationRepo.save(new Notification(user, "ITEM_UPDATE", null, user, "旧通知"));
        jdbc.update("UPDATE notification SET created_at = ? WHERE id = ?",
                Timestamp.from(now.minus(91, ChronoUnit.DAYS)), oldN.getId());
        Notification newN = notificationRepo.save(new Notification(user, "ITEM_UPDATE", null, user, "新通知"));

        // 审计：181 天前 / 今天
        AuditLog oldA = audit(team.getId(), user, "OLD");
        jdbc.update("UPDATE audit_log SET created_at = ? WHERE id = ?",
                Timestamp.from(now.minus(181, ChronoUnit.DAYS)), oldA.getId());
        AuditLog newA = audit(team.getId(), user, "NEW");

        RetentionPurgeService.PurgeResult result = purgeService.purge(now);
        // 通知/审计走 JPA 派生删除（待执行），flush 后才落库行数；事项/报告走 JDBC 已即时生效。
        em.flush();

        assertThat(result.itemsPurged()).isEqualTo(3); // 过期事项 + 过期父 + 过期子
        assertThat(result.itemsSkipped()).isEqualTo(1); // 被未删子项挡住的父项
        assertThat(result.reportsPurged()).isEqualTo(1);
        assertThat(result.notificationsPurged()).isEqualTo(1);
        assertThat(result.auditLogsPurged()).isEqualTo(1);

        // 存在性断言直接查库行数：物理删除走 JDBC/批量删除，JPA 一级缓存里的实体不作数。
        assertThat(rowCount("item", expiredItem.getId())).isZero();
        assertThat(rowCount("item", expiredParent.getId())).isZero();
        assertThat(rowCount("item", expiredChild.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM checklist_entry WHERE item_id = ?",
                Integer.class, expiredItem.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comment WHERE item_id = ?",
                Integer.class, expiredItem.getId())).isZero();
        assertThat(rowCount("item", freshItem.getId())).isOne();
        assertThat(rowCount("item", liveItem.getId())).isOne();
        assertThat(rowCount("item", blockedParent.getId())).isOne();
        assertThat(rowCount("item", liveChild.getId())).isOne();

        assertThat(rowCount("report", expiredReport.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_version WHERE report_id = ?",
                Integer.class, expiredReport.getId())).isZero();
        assertThat(rowCount("report", freshReport.getId())).isOne();
        assertThat(rowCount("report", liveReport.getId())).isOne();

        assertThat(rowCount("notification", oldN.getId())).isZero();
        assertThat(rowCount("notification", newN.getId())).isOne();
        assertThat(rowCount("audit_log", oldA.getId())).isZero();
        assertThat(rowCount("audit_log", newA.getId())).isOne();
    }

    // ---------- Q6 写入侧：业务动作必须真正落审计（M5 验收发现写点缺失后补线） ----------

    @Test
    void auditWritePoints_businessActionsAreLogged() throws Exception {
        MockHttpSession leaderS = register("m5w_leader", "写点组长");
        MockHttpSession poS = register("m5w_po", "写点PO");
        long leaderId = myId(leaderS);
        long poId = myId(poS);
        long teamId = createTeam(leaderS, "审计写点组", "AWP");
        addMember(teamId, poId, Enums.Role.PO);

        // PO 建任务 → CREATE_ITEM
        MvcResult created = mvc.perform(post("/api/v1/teams/" + teamId + "/items").session(poS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TASK\",\"title\":\"写点探针任务\"}"))
                .andExpect(status().isCreated()).andReturn();
        long itemId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        // 组长改 PO 角色 → CHANGE_ROLE（改成 MEMBER 再改回 PO 不影响后续；此处只验写点）
        mvc.perform(MockMvcRequestBuilders.patch("/api/v1/teams/" + teamId + "/members/" + poId + "/role")
                        .session(leaderS).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(MockMvcRequestBuilders.patch("/api/v1/teams/" + teamId + "/members/" + poId + "/role")
                        .session(leaderS).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"PO\"}"))
                .andExpect(status().isNoContent());

        // 组长生成周报 → GENERATE_REPORT；删除报告 → DELETE_REPORT
        MvcResult gen = mvc.perform(post("/api/v1/teams/" + teamId + "/reports/generate").session(leaderS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"WEEKLY\",\"periodStart\":\"2026-11-09\",\"periodEnd\":\"2026-11-15\"}"))
                .andExpect(status().isCreated()).andReturn();
        long reportId = ((Number) JsonPath.read(gen.getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/teams/" + teamId + "/reports/" + reportId)
                        .session(leaderS).with(csrf()))
                .andExpect(status().isNoContent());

        // 组长删任务 → DELETE_ITEM
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/teams/" + teamId + "/items/" + itemId)
                        .session(leaderS).with(csrf()))
                .andExpect(status().isNoContent());

        em.flush();
        java.util.List<String> actions = jdbc.queryForList(
                "SELECT action FROM audit_log WHERE team_id = ?", String.class, teamId);
        assertThat(actions).contains("CREATE_ITEM", "CHANGE_ROLE", "GENERATE_REPORT", "DELETE_REPORT", "DELETE_ITEM");

        // 读取侧能按动作筛到 DELETE_ITEM
        mvc.perform(get("/api/v1/teams/" + teamId + "/audit-logs?action=DELETE_ITEM").session(leaderS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("DELETE_ITEM"));
    }
}

package com.iterationboard.item;

import com.iterationboard.common.ApiException;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.Enums;
import com.iterationboard.repo.TeamMemberRepo;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事项直达解析（修订六契约 4）：GET /api/v1/items/{id}/resolution。
 * 全平台唯一事项入口 /i/:id 的后端依据：登录即可查；事项不存在（含已硬删）404；
 * 存在（含回收站软删）返回定位所需字段，deleted 看 item.deleted_at，hasAccess = 小组成员或平台管理员。
 *
 * rc4（D1）：不再经 JPA 物化 Item 实体——旧库若有现行枚举外的 status/type 值
 * （如 status='OPEN'、type='REQUIREMENT'），Hibernate 物化会抛 IllegalArgumentException 变 500。
 * 改用 JdbcTemplate 原生 SQL 只读标量列：type/status 以字符串读出，能匹配现行枚举则用枚举名，
 * 匹配不上则原样返回原始字符串（前端只把 type 当分支判断、status 当不透明展示值，原始串安全）。
 * rc4（B）：404 使用专属错误码 ITEM_NOT_FOUND，让前端能区分「业务 404」与「裸 404（接口不存在）」。
 */
@RestController
@RequestMapping("/api/v1/items")
public class ItemResolutionController {
    private final JdbcTemplate jdbc;
    private final TeamMemberRepo memberRepo;
    private final CurrentUser currentUser;

    public ItemResolutionController(JdbcTemplate jdbc, TeamMemberRepo memberRepo, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.memberRepo = memberRepo;
        this.currentUser = currentUser;
    }

    @GetMapping("/{id}/resolution")
    @Transactional(readOnly = true)
    public Map<String, Object> resolution(@PathVariable Long id) {
        AppUser me = currentUser.require();
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "select i.id, i.code, i.type, i.title, i.status, i.team_id, i.parent_id,"
                            + " i.iteration_id, i.deleted_at, t.name as team_name"
                            + " from item i left join team t on t.id = i.team_id where i.id = ?",
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "事项不存在");
        }
        long teamId = ((Number) row.get("team_id")).longValue();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ((Number) row.get("id")).longValue());
        m.put("code", row.get("code"));
        m.put("type", enumNameOrRaw((String) row.get("type"), Enums.ItemType.class));
        m.put("title", row.get("title"));
        m.put("status", enumNameOrRaw((String) row.get("status"), Enums.ItemStatus.class));
        m.put("teamId", teamId);
        m.put("teamName", row.get("team_name"));
        m.put("parentId", row.get("parent_id") == null ? null : ((Number) row.get("parent_id")).longValue());
        m.put("iterationId", row.get("iteration_id") == null ? null : ((Number) row.get("iteration_id")).longValue());
        m.put("deleted", row.get("deleted_at") != null);
        m.put("hasAccess", me.isPlatformAdmin()
                || memberRepo.findByTeamIdAndUserId(teamId, me.getId()).isPresent());
        return m;
    }

    /** 能匹配现行枚举则用枚举名；匹配不上（旧库枚举外值）原样返回原始字符串，不抛异常。 */
    private static <E extends Enum<E>> String enumNameOrRaw(String raw, Class<E> enumType) {
        if (raw == null) return null;
        for (E e : enumType.getEnumConstants()) {
            if (e.name().equals(raw)) return e.name();
        }
        return raw;
    }
}

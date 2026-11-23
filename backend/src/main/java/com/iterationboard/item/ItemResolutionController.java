package com.iterationboard.item;

import com.iterationboard.common.ApiException;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.Item;
import com.iterationboard.repo.ItemRepo;
import com.iterationboard.repo.TeamMemberRepo;
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
 * 存在（含回收站软删）返回定位所需字段，deleted 看 item.deletedAt，hasAccess = 小组成员或平台管理员。
 */
@RestController
@RequestMapping("/api/v1/items")
public class ItemResolutionController {
    private final ItemRepo itemRepo;
    private final TeamMemberRepo memberRepo;
    private final CurrentUser currentUser;

    public ItemResolutionController(ItemRepo itemRepo, TeamMemberRepo memberRepo, CurrentUser currentUser) {
        this.itemRepo = itemRepo;
        this.memberRepo = memberRepo;
        this.currentUser = currentUser;
    }

    @GetMapping("/{id}/resolution")
    @Transactional(readOnly = true)
    public Map<String, Object> resolution(@PathVariable Long id) {
        AppUser me = currentUser.require();
        Item item = itemRepo.findById(id).orElseThrow(() -> ApiException.notFound("事项不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("code", item.getCode());
        m.put("type", item.getType().name());
        m.put("title", item.getTitle());
        m.put("status", item.getStatus().name());
        m.put("teamId", item.getTeam().getId());
        m.put("teamName", item.getTeam().getName());
        m.put("parentId", item.getParent() == null ? null : item.getParent().getId());
        m.put("iterationId", item.getIteration() == null ? null : item.getIteration().getId());
        m.put("deleted", item.getDeletedAt() != null);
        m.put("hasAccess", me.isPlatformAdmin()
                || memberRepo.findByTeamIdAndUserId(item.getTeam().getId(), me.getId()).isPresent());
        return m;
    }
}

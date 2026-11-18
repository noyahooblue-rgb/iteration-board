package com.iterationboard.item;

import com.iterationboard.common.ApiException;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.Role;
import com.iterationboard.repo.ItemLabelRepo;
import com.iterationboard.repo.LabelRepo;
import com.iterationboard.team.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标签管理：
 * 增删改仅组长/PO；组内名称唯一（重名 409）；删除在用标签须 force=true，
 * 否则 409 LABEL_IN_USE 并附使用数。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/labels")
@Transactional
public class LabelController {
    private final LabelRepo labelRepo;
    private final ItemLabelRepo itemLabelRepo;
    private final TeamService teamService;
    private final CurrentUser currentUser;

    public LabelController(LabelRepo labelRepo, ItemLabelRepo itemLabelRepo,
                           TeamService teamService, CurrentUser currentUser) {
        this.labelRepo = labelRepo;
        this.itemLabelRepo = itemLabelRepo;
        this.teamService = teamService;
        this.currentUser = currentUser;
    }

    public record LabelRequest(String name, String color) {}

    private long usage(Long labelId) {
        return itemLabelRepo.countByLabelId(labelId);
    }

    private Map<String, Object> mapFast(Label l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("teamId", l.getTeam().getId());
        m.put("name", l.getName());
        m.put("color", l.getColor());
        m.put("usageCount", usage(l.getId()));
        return m;
    }

    private TeamMember requireLeaderOrPo(Long teamId, AppUser u) {
        TeamMember m = teamService.requireMember(teamId, u);
        if (m.getRole() != Role.LEADER && m.getRole() != Role.PO) {
            throw ApiException.forbidden("仅组长或 PO 可管理标签");
        }
        return m;
    }

    private Label requireLabel(Long teamId, Long labelId) {
        return labelRepo.findById(labelId)
                .filter(l -> l.getTeam().getId().equals(teamId))
                .orElseThrow(() -> ApiException.notFound("标签不存在"));
    }

    private String validName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 32) {
            throw ApiException.validation("标签名不能为空且不超过 32 字");
        }
        return name.trim();
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        teamService.requireMember(teamId, u);
        return labelRepo.findByTeamId(teamId).stream().map(this::mapFast).toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable Long teamId, @RequestBody LabelRequest req) {
        AppUser u = currentUser.require();
        requireLeaderOrPo(teamId, u);
        String name = validName(req.name());
        if (labelRepo.findByTeamIdAndName(teamId, name).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "LABEL_NAME_TAKEN", "组内已有同名标签");
        }
        Label l = new Label();
        l.setTeam(teamService.requireTeam(teamId));
        l.setName(name);
        l.setColor(req.color());
        labelRepo.save(l);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapFast(l));
    }

    @PatchMapping("/{labelId}")
    public Map<String, Object> patch(@PathVariable Long teamId, @PathVariable Long labelId,
                                     @RequestBody LabelRequest req) {
        AppUser u = currentUser.require();
        requireLeaderOrPo(teamId, u);
        Label l = requireLabel(teamId, labelId);
        if (req.name() != null) {
            String name = validName(req.name());
            labelRepo.findByTeamIdAndName(teamId, name)
                    .filter(other -> !other.getId().equals(labelId))
                    .ifPresent(other -> { throw new ApiException(HttpStatus.CONFLICT, "LABEL_NAME_TAKEN", "组内已有同名标签"); });
            l.setName(name);
        }
        if (req.color() != null) l.setColor(req.color());
        labelRepo.save(l);
        return mapFast(l);
    }

    @DeleteMapping("/{labelId}")
    public ResponseEntity<Void> delete(@PathVariable Long teamId, @PathVariable Long labelId,
                                       @RequestParam(required = false, defaultValue = "false") boolean force) {
        AppUser u = currentUser.require();
        requireLeaderOrPo(teamId, u);
        Label l = requireLabel(teamId, labelId);
        long used = usage(labelId);
        if (used > 0 && !force) {
            throw new ApiException(HttpStatus.CONFLICT, "LABEL_IN_USE",
                    "标签正在被 " + used + " 个事项使用，确认删除请带 force=true",
                    Map.of("usageCount", used));
        }
        if (used > 0) itemLabelRepo.deleteByLabelId(labelId);
        labelRepo.delete(l);
        return ResponseEntity.noContent().build();
    }
}

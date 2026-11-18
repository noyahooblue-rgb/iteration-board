package com.iterationboard.item;

import com.iterationboard.common.ApiException;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.config.Dtos;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.SavedFilter;
import com.iterationboard.repo.SavedFilterRepo;
import com.iterationboard.team.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** M2 团队级查询与保存筛选：回收站、待办池、搜索、saved-filters。 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}")
public class WorkspaceController {
    private final ItemService itemService;
    private final SavedFilterRepo savedFilterRepo;
    private final TeamService teamService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final com.iterationboard.report.ReportService reportService;
    private final com.iterationboard.repo.UserRepo userRepo;

    public WorkspaceController(ItemService itemService, SavedFilterRepo savedFilterRepo,
                               TeamService teamService, CurrentUser currentUser, ObjectMapper objectMapper,
                               com.iterationboard.report.ReportService reportService,
                               com.iterationboard.repo.UserRepo userRepo) {
        this.itemService = itemService;
        this.savedFilterRepo = savedFilterRepo;
        this.teamService = teamService;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.reportService = reportService;
        this.userRepo = userRepo;
    }

    public record SavedFilterRequest(String name, JsonNode criteria) {}

    /**
     * Q9：统一回收站——事项与报告合并返回，按 type=ITEM|REPORT|ALL（默认 ALL）筛选。
     * 每条含 kind/id/标题/删除时间/删除人；报告侧沿用 的可见口径（有编辑权者可见）。
     */
    @GetMapping("/trash")
    public List<java.util.Map<String, Object>> trash(@PathVariable Long teamId,
                                                     @RequestParam(required = false, defaultValue = "ALL") String type) {
        AppUser u = currentUser.require();
        String t = type == null ? "ALL" : type.toUpperCase();
        if (!List.of("ITEM", "REPORT", "ALL").contains(t)) {
            throw ApiException.validation("type 须为 ITEM / REPORT / ALL");
        }
        List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (!"REPORT".equals(t)) {
            for (var item : itemService.trash(teamId, u)) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("kind", "ITEM");
                m.put("id", item.getId());
                m.put("code", item.getCode());
                m.put("itemType", item.getType().name());
                m.put("title", item.getTitle());
                m.put("deletedAt", item.getDeletedAt());
                m.put("deletedById", item.getDeletedById());
                m.put("deletedByName", userName(item.getDeletedById()));
                out.add(m);
            }
        }
        if (!"ITEM".equals(t)) {
            for (var r : reportService.trash(teamId, u)) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("kind", "REPORT");
                m.put("id", r.getId());
                m.put("code", null);
                m.put("reportKind", r.getKind());
                m.put("title", r.getTitle());
                m.put("deletedAt", r.getDeletedAt());
                m.put("deletedById", r.getDeletedById());
                m.put("deletedByName", userName(r.getDeletedById()));
                out.add(m);
            }
        }
        out.sort((a, b) -> {
            java.time.Instant x = (java.time.Instant) a.get("deletedAt");
            java.time.Instant y = (java.time.Instant) b.get("deletedAt");
            if (x == null && y == null) return 0;
            if (x == null) return 1;
            if (y == null) return -1;
            return y.compareTo(x);
        });
        return out;
    }

    private String userName(Long userId) {
        return userId == null ? null : userRepo.findById(userId).map(AppUser::getDisplayName).orElse(null);
    }

    @GetMapping("/backlog")
    public List<Dtos.BacklogEntryDto> backlog(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        return itemService.backlog(teamId, u);
    }

    @GetMapping("/search")
    public List<Dtos.ItemDto> search(@PathVariable Long teamId, @RequestParam String q) {
        AppUser u = currentUser.require();
        return itemService.search(teamId, u, q).stream().map(i -> itemService.toDto(i, u)).toList();
    }

    @GetMapping("/saved-filters")
    public List<Dtos.SavedFilterDto> savedFilters(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        teamService.requireMember(teamId, u);
        return savedFilterRepo.findByTeamIdAndUserIdOrderByCreatedAtAsc(teamId, u.getId())
                .stream().map(this::toDto).toList();
    }

    @PostMapping("/saved-filters")
    public ResponseEntity<Dtos.SavedFilterDto> createSavedFilter(@PathVariable Long teamId,
                                                                 @RequestBody SavedFilterRequest req) {
        AppUser u = currentUser.require();
        teamService.requireMember(teamId, u);
        if (req.name() == null || req.name().isBlank()) throw ApiException.validation("筛选名称不能为空");
        if (req.criteria() == null) throw ApiException.validation("筛选条件不能为空");
        //：保存的筛选每人最多 20 个，第 21 个 400
        if (savedFilterRepo.findByTeamIdAndUserIdOrderByCreatedAtAsc(teamId, u.getId()).size() >= 20) {
            throw ApiException.validation("保存的筛选最多 20 个，请先删除不用的筛选");
        }
        SavedFilter f = new SavedFilter();
        f.setTeam(teamService.requireTeam(teamId));
        f.setUser(u);
        f.setName(req.name());
        f.setCriteriaJson(req.criteria().toString());
        savedFilterRepo.save(f);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(f));
    }

    @DeleteMapping("/saved-filters/{filterId}")
    public ResponseEntity<Void> deleteSavedFilter(@PathVariable Long teamId, @PathVariable Long filterId) {
        AppUser u = currentUser.require();
        teamService.requireMember(teamId, u);
        SavedFilter f = savedFilterRepo.findByIdAndTeamIdAndUserId(filterId, teamId, u.getId())
                .orElseThrow(() -> ApiException.notFound("保存的筛选不存在"));
        savedFilterRepo.delete(f);
        return ResponseEntity.noContent().build();
    }

    private Dtos.SavedFilterDto toDto(SavedFilter f) {
        JsonNode criteria;
        try {
            criteria = objectMapper.readTree(f.getCriteriaJson());
        } catch (Exception e) {
            criteria = objectMapper.createObjectNode();
        }
        return new Dtos.SavedFilterDto(f.getId(), f.getName(), criteria, f.getCreatedAt());
    }
}

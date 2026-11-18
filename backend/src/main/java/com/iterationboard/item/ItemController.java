package com.iterationboard.item;

import tools.jackson.databind.JsonNode;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.config.Dtos;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.Enums;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/items")
public class ItemController {
    private final ItemService itemService;
    private final NudgeService nudgeService;
    private final CurrentUser currentUser;
    private final com.iterationboard.common.IdempotencyHelper idempotency;

    public ItemController(ItemService itemService, NudgeService nudgeService, CurrentUser currentUser,
                          com.iterationboard.common.IdempotencyHelper idempotency) {
        this.itemService = itemService;
        this.nudgeService = nudgeService;
        this.currentUser = currentUser;
        this.idempotency = idempotency;
    }

    public record CreateItemRequest(Enums.ItemType type, String title, String description, Enums.Priority priority,
                                    String destination, Long assigneeId, List<Long> ownerIds, List<Long> inviteeIds, Long acceptorId,
                                    LocalDate dueDate, BigDecimal estimate, Long parentId, Long iterationId,
                                    List<String> labelNames, Enums.StoryCategory storyCategory, Long poId) {}
    public record NewStoryInput(String title, Enums.StoryCategory storyCategory) {}
    public record TransitionRequest(ItemTransitionService.Action action, Integer version, String reason, Long newAssigneeId, Long newOwnerId,
                                    Long replaceUserId, List<Long> reopenItemIds, List<NewStoryInput> newStories) {}
    public record JoinRequest(Integer version) {}
    public record ReorderRequest(List<Long> orderedIds) {}
    public record ProgressRequest(String text, Integer percent, Boolean blocked, String blockReason, String nextStep, String kind) {}
    public record NudgeRespondRequest(String response) {}
    public record CommentRequest(String content, List<Long> mentionIds) {}
    public record ChecklistCreateRequest(String text, Integer sortOrder) {}
    public record ChecklistPatchRequest(String text, Boolean done, Integer sortOrder) {}

    @GetMapping
    public List<Dtos.ItemDto> list(@PathVariable Long teamId,
                                   @RequestParam(required = false) Enums.ItemStatus status,
                                   @RequestParam(required = false) Long ownerId,
                                   @RequestParam(required = false) Long assigneeId,
                                   @RequestParam(required = false) Enums.ItemType type,
                                   @RequestParam(required = false) Long iterationId,
                                   @RequestParam(required = false, defaultValue = "all") String scope) {
        AppUser u = currentUser.require();
        Long effectiveOwner = ownerId != null ? ownerId : assigneeId;
        return itemService.list(teamId, u, status, effectiveOwner, type, iterationId, scope)
                .stream().map(i -> itemService.toDto(i, u)).toList();
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long teamId, @RequestBody CreateItemRequest req,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        AppUser u = currentUser.require();
        String scope = "POST /teams/" + teamId + "/items";
        if (idemKey != null) {
            var replay = idempotency.replay(u.getId(), scope, idemKey);
            if (replay.isPresent()) return replay.get();
        }
        List<Long> owners = req.ownerIds() != null && !req.ownerIds().isEmpty()
                ? req.ownerIds()
                : (req.assigneeId() != null ? List.of(req.assigneeId()) : null);
        var item = itemService.create(teamId, u, new ItemService.CreateItemCmd(
                req.type(), req.title(), req.description(), req.priority(), req.destination(),
                owners, req.inviteeIds(), req.acceptorId(), req.dueDate(), req.estimate(),
                req.parentId(), req.iterationId(), req.labelNames(), req.storyCategory(), req.poId()));
        Dtos.ItemDto dto = itemService.toDto(item, u);
        if (idemKey != null) return idempotency.remember(u.getId(), scope, idemKey, HttpStatus.CREATED.value(), dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping("/{itemId}")
    public Dtos.ItemDto get(@PathVariable Long teamId, @PathVariable Long itemId) {
        AppUser u = currentUser.require();
        return itemService.toDto(itemService.get(teamId, itemId, u), u);
    }

    @PatchMapping("/{itemId}")
    public Dtos.ItemDto patch(@PathVariable Long teamId, @PathVariable Long itemId, @RequestBody JsonNode body) {
        AppUser u = currentUser.require();
        if (!body.hasNonNull("version")) {
            throw com.iterationboard.common.ApiException.validation("version 必填");
        }
        var cmd = new ItemService.PatchCmd(
                body.get("version").asInt(),
                body.hasNonNull("title") ? body.get("title").asText() : null,
                body.hasNonNull("description") ? body.get("description").asText() : null,
                body.hasNonNull("priority") ? Enums.Priority.valueOf(body.get("priority").asText()) : null,
                body.has("acceptorId") && !body.get("acceptorId").isNull() ? body.get("acceptorId").asLong() : null,
                body.has("acceptorId"),
                body.has("dueDate") && !body.get("dueDate").isNull() ? LocalDate.parse(body.get("dueDate").asText()) : null,
                body.has("dueDate"),
                body.has("estimate") && !body.get("estimate").isNull() ? body.get("estimate").decimalValue() : null,
                body.has("estimate"),
                body.has("iterationId") && !body.get("iterationId").isNull() ? body.get("iterationId").asLong() : null,
                body.has("iterationId"),
                body.has("parentId") && !body.get("parentId").isNull() ? body.get("parentId").asLong() : null,
                body.has("parentId"),
                body.hasNonNull("storyCategory") ? Enums.StoryCategory.valueOf(body.get("storyCategory").asText()) : null,
                body.has("poId") && !body.get("poId").isNull() ? body.get("poId").asLong() : null,
                body.has("poId"),
                body.has("ownerIds") && !body.get("ownerIds").isNull() ? longList(body.get("ownerIds")) : null,
                body.has("ownerIds"),
                body.has("labelNames") && !body.get("labelNames").isNull() ? stringList(body.get("labelNames")) : null,
                body.has("labelNames"));
        return itemService.toDto(itemService.patch(teamId, itemId, u, cmd), u);
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> delete(@PathVariable Long teamId, @PathVariable Long itemId) {
        AppUser u = currentUser.require();
        itemService.softDelete(teamId, itemId, u);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{itemId}/restore")
    public Dtos.ItemDto restore(@PathVariable Long teamId, @PathVariable Long itemId) {
        AppUser u = currentUser.require();
        return itemService.toDto(itemService.restore(teamId, itemId, u), u);
    }

    @PostMapping("/{itemId}/join")
    public ResponseEntity<?> join(@PathVariable Long teamId, @PathVariable Long itemId, @RequestBody JoinRequest req,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        AppUser u = currentUser.require();
        if (req.version() == null) throw com.iterationboard.common.ApiException.validation("version 必填");
        // Q13：加入共同负责支持幂等键，同键 10 分钟只生效一次
        String scope = "POST /teams/" + teamId + "/items/" + itemId + "/join";
        if (idemKey != null) {
            var replay = idempotency.replay(u.getId(), scope, idemKey);
            if (replay.isPresent()) return replay.get();
        }
        Dtos.ItemDto dto = itemService.toDto(itemService.join(teamId, itemId, u, req.version()), u);
        if (idemKey != null) return idempotency.remember(u.getId(), scope, idemKey, HttpStatus.OK.value(), dto);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/{itemId}/stories")
    public List<Dtos.ItemDto> stories(@PathVariable Long teamId, @PathVariable Long itemId) {
        AppUser u = currentUser.require();
        return itemService.storiesOf(teamId, itemId, u).stream().map(i -> itemService.toDto(i, u)).toList();
    }

    @PostMapping("/{itemId}/stories/reorder")
    public List<Dtos.ItemDto> reorderStories(@PathVariable Long teamId, @PathVariable Long itemId,
                                             @RequestBody ReorderRequest req) {
        AppUser u = currentUser.require();
        return itemService.reorderStories(teamId, itemId, u, req.orderedIds())
                .stream().map(i -> itemService.toDto(i, u)).toList();
    }

    @PostMapping("/{itemId}/transitions")
    public ResponseEntity<?> transition(@PathVariable Long teamId, @PathVariable Long itemId, @RequestBody TransitionRequest req,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        AppUser u = currentUser.require();
        if (req.action() == null) throw com.iterationboard.common.ApiException.validation("action 必填");
        if (req.version() == null) throw com.iterationboard.common.ApiException.validation("version 必填");
        // Q13：认领（CLAIM）支持幂等键，同键 10 分钟只生效一次
        String scope = "POST /teams/" + teamId + "/items/" + itemId + "/transitions/" + req.action();
        boolean idem = idemKey != null && req.action() == ItemTransitionService.Action.CLAIM;
        if (idem) {
            var replay = idempotency.replay(u.getId(), scope, idemKey);
            if (replay.isPresent()) return replay.get();
        }
        Long target = req.newOwnerId() != null ? req.newOwnerId() : req.newAssigneeId();
        var extras = new ItemTransitionService.Extras(req.replaceUserId(), req.reopenItemIds(),
                req.newStories() == null ? null : req.newStories().stream()
                        .map(s -> new ItemTransitionService.NewStoryInput(s.title(), s.storyCategory())).toList());
        var item = itemService.transition(teamId, itemId, u, req.action(), req.version(), req.reason(), target, extras);
        Dtos.ItemDto dto = itemService.toDto(item, u);
        if (idem) return idempotency.remember(u.getId(), scope, idemKey, HttpStatus.OK.value(), dto);
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/{itemId}/progress")
    public ResponseEntity<Dtos.ProgressDto> progress(@PathVariable Long teamId, @PathVariable Long itemId,
                                                     @RequestBody ProgressRequest req) {
        AppUser u = currentUser.require();
        var p = itemService.addProgress(teamId, itemId, u, req.text(), req.percent(), req.blocked(), req.blockReason(), req.nextStep(),
                req.kind() == null ? "NORMAL" : req.kind());
        return ResponseEntity.status(HttpStatus.CREATED).body(Dtos.ProgressDto.of(p));
    }

    @PostMapping("/{itemId}/nudge")
    public ResponseEntity<?> nudge(@PathVariable Long teamId, @PathVariable Long itemId,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        AppUser u = currentUser.require();
        // Q13：催一下创建支持幂等键，同键 10 分钟只生效一次
        String scope = "POST /teams/" + teamId + "/items/" + itemId + "/nudge";
        if (idemKey != null) {
            var replay = idempotency.replay(u.getId(), scope, idemKey);
            if (replay.isPresent()) return replay.get();
        }
        var result = nudgeService.nudge(teamId, itemId, u);
        if (idemKey != null) return idempotency.remember(u.getId(), scope, idemKey, HttpStatus.OK.value(), result);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{itemId}/nudge/respond")
    public ResponseEntity<?> nudgeRespond(@PathVariable Long teamId, @PathVariable Long itemId,
                                          @RequestBody NudgeRespondRequest req,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        AppUser u = currentUser.require();
        // Q13：回应催办支持幂等键，同键 10 分钟只生效一次
        String scope = "POST /teams/" + teamId + "/items/" + itemId + "/nudge/respond";
        if (idemKey != null) {
            var replay = idempotency.replay(u.getId(), scope, idemKey);
            if (replay.isPresent()) return replay.get();
        }
        var result = nudgeService.respond(teamId, itemId, u, req.response());
        if (idemKey != null) return idempotency.remember(u.getId(), scope, idemKey, HttpStatus.OK.value(), result);
        return ResponseEntity.ok(result);
    }

    private static List<Long> longList(JsonNode node) {
        List<Long> out = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(n -> out.add(n.asLong()));
        return out;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(n -> out.add(n.asText()));
        return out;
    }

    @GetMapping("/{itemId}/progress-list")
    public List<Dtos.ProgressDto> progressList(@PathVariable Long teamId, @PathVariable Long itemId) {
        AppUser u = currentUser.require();
        return itemService.progressList(teamId, itemId, u).stream().map(Dtos.ProgressDto::of).toList();
    }

    @GetMapping("/{itemId}/comments")
    public List<Dtos.CommentDto> comments(@PathVariable Long teamId, @PathVariable Long itemId) {
        AppUser u = currentUser.require();
        return itemService.comments(teamId, itemId, u).stream().map(Dtos.CommentDto::of).toList();
    }

    @PostMapping("/{itemId}/comments")
    public ResponseEntity<Dtos.CommentDto> addComment(@PathVariable Long teamId, @PathVariable Long itemId,
                                                      @RequestBody CommentRequest req) {
        AppUser u = currentUser.require();
        var c = itemService.addComment(teamId, itemId, u, req.content(), req.mentionIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(Dtos.CommentDto.of(c));
    }

    @PatchMapping("/{itemId}/comments/{commentId}")
    public Dtos.CommentDto patchComment(@PathVariable Long teamId, @PathVariable Long itemId,
                                        @PathVariable Long commentId, @RequestBody CommentRequest req) {
        AppUser u = currentUser.require();
        return Dtos.CommentDto.of(itemService.patchComment(teamId, itemId, commentId, u, req.content()));
    }

    @DeleteMapping("/{itemId}/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@PathVariable Long teamId, @PathVariable Long itemId,
                                              @PathVariable Long commentId) {
        AppUser u = currentUser.require();
        itemService.deleteComment(teamId, itemId, commentId, u);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{itemId}/checklist")
    public List<Dtos.ChecklistDto> checklist(@PathVariable Long teamId, @PathVariable Long itemId) {
        AppUser u = currentUser.require();
        return itemService.checklist(teamId, itemId, u).stream().map(Dtos.ChecklistDto::of).toList();
    }

    @PostMapping("/{itemId}/checklist")
    public ResponseEntity<Dtos.ChecklistDto> addChecklist(@PathVariable Long teamId, @PathVariable Long itemId,
                                                           @RequestBody ChecklistCreateRequest req) {
        AppUser u = currentUser.require();
        var e = itemService.addChecklistEntry(teamId, itemId, u, req.text(), req.sortOrder());
        return ResponseEntity.status(HttpStatus.CREATED).body(Dtos.ChecklistDto.of(e));
    }

    @PatchMapping("/{itemId}/checklist/{entryId}")
    public Dtos.ChecklistDto patchChecklist(@PathVariable Long teamId, @PathVariable Long itemId,
                                            @PathVariable Long entryId, @RequestBody ChecklistPatchRequest req) {
        AppUser u = currentUser.require();
        var e = itemService.updateChecklistEntry(teamId, itemId, entryId, u, req.text(), req.done(), req.sortOrder());
        return Dtos.ChecklistDto.of(e);
    }

    @DeleteMapping("/{itemId}/checklist/{entryId}")
    public ResponseEntity<Void> deleteChecklist(@PathVariable Long teamId, @PathVariable Long itemId,
                                                @PathVariable Long entryId) {
        AppUser u = currentUser.require();
        itemService.deleteChecklistEntry(teamId, itemId, entryId, u);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{itemId}/activities")
    public List<Dtos.ActivityDto> activities(@PathVariable Long teamId, @PathVariable Long itemId) {
        AppUser u = currentUser.require();
        return itemService.activities(teamId, itemId, u).stream().map(Dtos.ActivityDto::of).toList();
    }
}

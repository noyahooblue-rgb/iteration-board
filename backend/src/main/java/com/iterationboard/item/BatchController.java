package com.iterationboard.item;

import com.iterationboard.config.CurrentUser;
import com.iterationboard.config.Dtos;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.Enums;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/dispatch-batches")
public class BatchController {
    private final BatchService batchService;
    private final CurrentUser currentUser;
    private final com.iterationboard.common.IdempotencyHelper idempotency;

    public BatchController(BatchService batchService, CurrentUser currentUser,
                           com.iterationboard.common.IdempotencyHelper idempotency) {
        this.batchService = batchService;
        this.currentUser = currentUser;
        this.idempotency = idempotency;
    }

    public record BatchRequest(Enums.ItemType type, String title, String description, Enums.Priority priority,
                               List<Long> memberIds, Enums.StoryCategory storyCategory, Long parentId,
                               LocalDate dueDate, BigDecimal estimate, Long iterationId,
                               Boolean includeCreator) {}
    public record CancelRequest(String reason) {}

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long teamId, @RequestBody BatchRequest req,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        AppUser u = currentUser.require();
        String scope = "POST /teams/" + teamId + "/dispatch-batches";
        if (idemKey != null) {
            var replay = idempotency.replay(u.getId(), scope, idemKey);
            if (replay.isPresent()) return replay.get();
        }
        var summary = batchService.create(teamId, u, new BatchService.BatchCmd(
                req.type(), req.title(), req.description(), req.priority(), req.memberIds(),
                req.storyCategory(), req.parentId(), req.dueDate(), req.estimate(), req.iterationId(),
                req.includeCreator()));
        if (idemKey != null) return idempotency.remember(u.getId(), scope, idemKey, HttpStatus.CREATED.value(), summary);
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    @GetMapping("/{batchId}")
    public Dtos.BatchSummaryDto get(@PathVariable Long teamId, @PathVariable Long batchId) {
        AppUser u = currentUser.require();
        return batchService.summary(teamId, batchId, u);
    }

    @PostMapping("/{batchId}/nudge")
    public Dtos.BatchNudgeDto nudge(@PathVariable Long teamId, @PathVariable Long batchId) {
        AppUser u = currentUser.require();
        return batchService.nudge(teamId, batchId, u);
    }

    @PostMapping("/{batchId}/cancel")
    public Dtos.BatchSummaryDto cancel(@PathVariable Long teamId, @PathVariable Long batchId,
                                       @RequestBody CancelRequest req) {
        AppUser u = currentUser.require();
        return batchService.cancel(teamId, batchId, u, req.reason());
    }
}

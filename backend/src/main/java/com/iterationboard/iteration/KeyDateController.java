package com.iterationboard.iteration;

import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.AppUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 关键日期删除（路径在小组维度：/teams/{teamId}/key-dates/{keyDateId}）。 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/key-dates")
public class KeyDateController {
    private final IterationService iterationService;
    private final CurrentUser currentUser;

    public KeyDateController(IterationService iterationService, CurrentUser currentUser) {
        this.iterationService = iterationService;
        this.currentUser = currentUser;
    }

    @DeleteMapping("/{keyDateId}")
    public ResponseEntity<Void> delete(@PathVariable Long teamId, @PathVariable Long keyDateId) {
        AppUser u = currentUser.require();
        iterationService.deleteKeyDate(teamId, keyDateId, u);
        return ResponseEntity.noContent().build();
    }
}

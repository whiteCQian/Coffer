package com.coffer.model.runtime.api;

import com.coffer.auth.service.OwnerAuthorization;
import com.coffer.dto.Result;
import com.coffer.model.runtime.ModelExecutionSnapshotService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reuse only the owner's previously confirmed CHAT purpose for this exact configuration. */
@RestController
@RequestMapping("/api/model-execution")
public class ChatTargetController {
    private final OwnerAuthorization authorization;
    private final ModelExecutionSnapshotService snapshots;
    private final JdbcTemplate jdbc;

    public ChatTargetController(OwnerAuthorization authorization, ModelExecutionSnapshotService snapshots, JdbcTemplate jdbc) {
        this.authorization = authorization;
        this.snapshots = snapshots;
        this.jdbc = jdbc;
    }

    @GetMapping("/chat-target")
    public Result<Map<String, Object>> target() {
        Long owner = authorization.requireOwner();
        var target = snapshots.preview();
        Long confirmed = jdbc.queryForObject("SELECT COUNT(*) FROM model_execution_snapshot "
                + "WHERE owner_id=? AND configuration_version=? AND purpose='CHAT' AND confirmed_at IS NOT NULL",
                Long.class, owner, target.configurationVersion());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("configurationVersion", target.configurationVersion());
        response.put("mode", target.mode());
        response.put("targets", target.targets());
        response.put("chatConsentApproved", confirmed != null && confirmed > 0);
        return Result.success(response);
    }
}

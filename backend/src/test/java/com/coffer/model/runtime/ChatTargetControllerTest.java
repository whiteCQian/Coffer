package com.coffer.model.runtime;

import com.coffer.auth.service.OwnerAuthorization;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.api.ChatTargetController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatTargetControllerTest {
    @Test void onlyTheSameOwnerConfigurationAndChatPurposeAreReusable() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:chat-consent-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE model_execution_snapshot(owner_id BIGINT, configuration_version VARCHAR(64), purpose VARCHAR(32), confirmed_at TIMESTAMP)");
        var owner = mock(OwnerAuthorization.class);
        when(owner.requireOwner()).thenReturn(1L);
        var snapshots = mock(ModelExecutionSnapshotService.class);
        when(snapshots.preview()).thenReturn(new ModelExecutionSnapshotService.TargetPreview("current", GovernanceRunMode.LOCAL, List.of()));
        var controller = new ChatTargetController(owner, snapshots, jdbc);
        assertThat(controller.target().getData().get("chatConsentApproved")).isEqualTo(false);
        jdbc.update("INSERT INTO model_execution_snapshot VALUES(1,'current','UPLOAD',CURRENT_TIMESTAMP), (2,'current','CHAT',CURRENT_TIMESTAMP), (1,'old','CHAT',CURRENT_TIMESTAMP)");
        assertThat(controller.target().getData().get("chatConsentApproved")).isEqualTo(false);
        jdbc.update("INSERT INTO model_execution_snapshot VALUES(1,'current','CHAT',CURRENT_TIMESTAMP)");
        assertThat(controller.target().getData().get("chatConsentApproved")).isEqualTo(true);
        when(snapshots.preview()).thenReturn(new ModelExecutionSnapshotService.TargetPreview("changed", GovernanceRunMode.LOCAL, List.of()));
        assertThat(controller.target().getData().get("chatConsentApproved")).isEqualTo(false);
        when(owner.requireOwner()).thenThrow(new org.springframework.security.access.AccessDeniedException("denied"));
        assertThatThrownBy(controller::target).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}

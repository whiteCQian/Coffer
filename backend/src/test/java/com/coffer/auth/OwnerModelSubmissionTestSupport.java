package com.coffer.auth;

import com.coffer.auth.service.TenantContext;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelExecutionContext;
import com.coffer.model.runtime.ModelExecutionSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Owner fixture for controller tests that exercise a model-consented submission route. */
public abstract class OwnerModelSubmissionTestSupport extends OwnerTestSupport {
    @MockitoBean ModelExecutionSnapshotService fixtureSnapshots;

    @BeforeEach
    void provideConsentedModelSnapshot() {
        when(fixtureSnapshots.capture(nullable(String.class), anyBoolean(), anyString()))
                .thenAnswer(invocation -> new ModelExecutionContext.Snapshot(
                        "fixture-snapshot", TenantContext.requireOwnerId(), "fixture-version",
                        GovernanceRunMode.API, Map.of()));
    }
}

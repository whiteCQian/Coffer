package com.coffer.model.runtime;

import com.coffer.governance.domain.GovernanceRunMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRuntimeModeContextTest {

    @Test
    void restoresPreviousSnapshotAfterTaskFinishes() {
        assertThat(ModelRuntimeModeContext.current()).isNull();

        String value = ModelRuntimeModeContext.withSnapshot(GovernanceRunMode.LOCAL, () -> {
            assertThat(ModelRuntimeModeContext.current()).isEqualTo(GovernanceRunMode.LOCAL);
            return "done";
        });

        assertThat(value).isEqualTo("done");
        assertThat(ModelRuntimeModeContext.current()).isNull();
    }
}

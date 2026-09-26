package com.coffer.agent;

import com.coffer.service.ChatSessionService;
import com.coffer.tool.*;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiAgentServiceTest {
    @Test void toolSchemaDoesNotLetModelChooseOwnerOrSession() {
        var tools = new AiAgentService.BoundTools(1L, "server-session", mock(ChatSessionService.class),
                mock(FileParsingTool.class), mock(TagGenerationTool.class), mock(FileSearchTool.class));
        var specifications = ToolSpecifications.toolSpecificationsFrom(tools);
        assertThat(specifications).extracting(s -> s.name()).containsExactlyInAnyOrder("parse_file", "search_files", "get_recent_uploads", "generate_tags");
        assertThat(specifications.toString()).doesNotContain("ownerId", "server-session");
    }
    @Test void boundToolsReauthorizeEveryInvocationBeforeDelegating() {
        var sessions = mock(ChatSessionService.class);
        var parser = mock(FileParsingTool.class);
        var tags = mock(TagGenerationTool.class);
        var search = mock(FileSearchTool.class);
        var tools = new AiAgentService.BoundTools(1L, "session", sessions, parser, tags, search);
        doThrow(new AccessDeniedException("revoked")).when(sessions).require(1L, "session");
        assertThatThrownBy(() -> tools.parse("2")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> tools.search("all")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(tools::recent).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> tools.tags("data")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(parser, tags, search);
    }
}

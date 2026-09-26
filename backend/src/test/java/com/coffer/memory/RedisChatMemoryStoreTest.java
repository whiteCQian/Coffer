package com.coffer.memory;

import com.coffer.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.security.access.AccessDeniedException;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Official codec preserves tool frames inside the authorized revision envelope. */
class RedisChatMemoryStoreTest {
    StringRedisTemplate redis;
    RedisChatMemoryStore store;
    OwnerMemoryId id = new OwnerMemoryId(1L, UUID.randomUUID().toString());
    @BeforeEach void setup() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        Map<String,String> rows = new HashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> rows.get(i.getArgument(0)));
        doAnswer(i -> { rows.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        var citations = mock(ChatCitationCollector.class);
        when(citations.revisions()).thenReturn(Map.of());
        store = new RedisChatMemoryStore(redis, new ObjectMapper(), mock(ChatSessionService.class), mock(PrivateFileAccess.class), citations);
    }
    @Test void roundTripUserAiAndToolFrames() {
        var request = ToolExecutionRequest.builder().id("call_01").name("search_files").arguments("{}").build();
        var messages = List.<ChatMessage>of(UserMessage.from("find"), AiMessage.from(request),
                ToolExecutionResultMessage.from("call_01", "search_files", "found"), AiMessage.from("done"));
        store.updateMessages(id, messages);
        var restored = store.getMessages(id);
        assertThat(restored).isEqualTo(messages);
        assertThat(((AiMessage) restored.get(1)).toolExecutionRequests().get(0).id()).isEqualTo("call_01");
        assertThat(restored.get(2)).isInstanceOf(ToolExecutionResultMessage.class);
    }
    @Test void absentAuthorizedMemoryIsEmptyButRawKeysAreDenied() {
        assertThat(store.getMessages(id)).isEmpty();
        clearInvocations(redis);
        assertThatThrownBy(() -> store.getMessages("1:guess")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> store.updateMessages("1:guess", List.of())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> store.deleteMessages("1:guess")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(redis);
    }
}

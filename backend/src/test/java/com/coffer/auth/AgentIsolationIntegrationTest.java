package com.coffer.auth;

import com.coffer.agent.AiAgentService;
import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.*;
import com.coffer.auth.service.*;
import com.coffer.config.*;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.file.domain.parse.ParseResult;
import com.coffer.memory.*;
import com.coffer.model.provider.*;
import com.coffer.service.*;
import com.coffer.tool.*;
import com.coffer.vector.*;
import com.coffer.tag.domain.*;
import com.coffer.tag.domain.Tag;
import com.coffer.tag.infrastructure.persistence.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.AopTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = AgentIsolationIntegrationTest.Config.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:r13;DB_CLOSE_DELAY=-1", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "spring.session.jdbc.initialize-schema=never"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class AgentIsolationIntegrationTest {
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration @EnableAspectJAutoProxy @EntityScan("com.coffer")
    @Import({HibernateTenantConfig.class, CurrentTenantResolver.class, OwnerAuthorization.class,
            OwnerAuthorizationAspect.class, SecurityConfig.class, AppUserDetailsService.class,
            PrivateFileAccess.class, ChatSessionService.class, ChatCitationCollector.class,
            ChatMemoryProvider.class, RedisChatMemoryStore.class, AiAgentService.class,
            ConversationService.class, FileSearchTool.class, FileParsingTool.class, HybridSearchService.class,
            com.coffer.controller.ChatController.class, com.coffer.file.api.FileController.class,
            com.coffer.file.application.FileService.class, com.coffer.file.application.assembler.FileResponseAssembler.class,
            com.coffer.exception.GlobalExceptionHandler.class})
    static class Config {
        @Bean StringRedisTemplate stringRedisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean ChatProvider model() { return mock(ChatProvider.class); }
        @Bean EmbeddingProvider embedding() { return mock(EmbeddingProvider.class); }
        @Bean RedisVectorStore vectors() { return mock(RedisVectorStore.class); }
        @Bean MinioStorageService storage() { return mock(MinioStorageService.class); }
        @Bean DocumentParseService parser() { return mock(DocumentParseService.class); }
        @Bean TagGenerationTool tagTool() { return mock(TagGenerationTool.class); }
        @Bean HybridSearchProperties properties() { return new HybridSearchProperties(); }
        @Bean com.coffer.file.application.FileUploadApplicationService upload() { return mock(com.coffer.file.application.FileUploadApplicationService.class); }
        @Bean com.coffer.file.application.FileLifecycleApplicationService lifecycle() { return mock(com.coffer.file.application.FileLifecycleApplicationService.class); }
    }
    @Autowired AppUserRepository users;
    @Autowired FileMetadataRepository files;
    @Autowired TagRepository tags;
    @Autowired FileTagMappingRepository mappings;
    @Autowired ChatSessionService sessions;
    @Autowired ChatMemoryProvider memory;
    @Autowired RedisChatMemoryStore store;
    @Autowired StringRedisTemplate redis;
    @Autowired ChatProvider model;
    @Autowired EmbeddingProvider embedding;
    @Autowired RedisVectorStore vectorProxy;
    @Autowired HybridSearchProperties properties;
    @Autowired HybridSearchService hybrid;
    @Autowired FileSearchTool search;
    @Autowired FileParsingTool parse;
    @Autowired DocumentParseService parser;
    @Autowired MinioStorageService storageProxy;
    @Autowired ChatCitationCollector citations;
    @Autowired ConversationService conversation;
    @Autowired AiAgentService agent;
    @Autowired PrivateFileAccess access;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired org.springframework.session.SessionRepository httpSessions;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired OwnerAuthorization authorization;
    AppUser a, b, admin;
    FileMetadata fa, fb;
    MinioStorageService storage;
    RedisVectorStore vectors;
    Map<String, String> redisValues;

    @BeforeEach void setup() throws Exception {
        TenantContext.clear(); SecurityContextHolder.clearContext(); citations.clear();
        String suffix = UUID.randomUUID().toString();
        a = users.save(new AppUser("a-" + suffix, "unused", AuthRole.USER));
        b = users.save(new AppUser("b-" + suffix, "unused", AuthRole.USER));
        admin = users.save(new AppUser("admin-" + suffix, "unused", AuthRole.ADMIN));
        fa = file(a, "A_PRIVATE"); fb = file(b, "B_SECRET");
        storage = AopTestUtils.getUltimateTargetObject(storageProxy);
        vectors = AopTestUtils.getUltimateTargetObject(vectorProxy);
        DocumentParseService parserMock = AopTestUtils.getUltimateTargetObject(parser);
        reset(redis, model, storage, vectors, embedding, parserMock);
        properties.setEnabled(false);
        redisValues = new ConcurrentHashMap<>();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> redisValues.get(i.getArgument(0)));
        doAnswer(i -> { redisValues.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        doAnswer(i -> { redisValues.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString());
        when(redis.delete(anyString())).thenAnswer(i -> redisValues.remove(i.getArgument(0)) != null);
        when(model.chat(any(ChatRequest.class))).thenReturn(reply("ok"));
        when(embedding.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{1, 0})));
        when(storage.getFileStream(isNull(), anyString())).thenAnswer(i ->
                new java.io.ByteArrayInputStream((i.<String>getArgument(1).contains("/" + a.getId() + "/") ? "A_CONTENT" : "B_CONTENT").getBytes(StandardCharsets.UTF_8)));
        when(parserMock.extractTextFromFile(anyString(), any())).thenAnswer(i -> ParseResult.success(
                new String(i.<java.io.InputStream>getArgument(1).readAllBytes(), StandardCharsets.UTF_8)));
    }
    @AfterEach void clear() { citations.clear(); TenantContext.clear(); SecurityContextHolder.clearContext(); }
    FileMetadata file(AppUser owner, String marker) throws Exception {
        return TenantContext.callAs(owner.getId(), () -> files.saveAndFlush(FileMetadata.builder()
                .fileName("shared-" + marker + ".txt").summary(marker).fileSize(9L).fileType("txt")
                .status(FileStatus.COMPLETED).storagePath("users/" + owner.getId() + "/files/a.txt").build()));
    }
    static ChatResponse reply(String text) { return ChatResponse.builder().aiMessage(AiMessage.from(text)).build(); }
    static ChatResponse tool(String name, String args) {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString()).name(name).arguments(args).build())).build();
    }

    @Test void lexicalFallbackTagsAndRecentUploadsStayWithOwner() {
        TenantContext.runAs(b.getId(), () -> {
            Tag tag = tags.save(Tag.builder().tagName("secret-tag").build());
            mappings.save(FileTagMapping.builder().fileId(fb.getId()).tagId(tag.getId()).confirmationStatus(ConfirmationStatus.CONFIRMED).build());
        });
        TenantContext.runAs(a.getId(), () -> {
            Tag pending = tags.save(Tag.builder().tagName("unconfirmed-tag").build());
            mappings.save(FileTagMapping.builder().fileId(fa.getId()).tagId(pending.getId()).confirmationStatus(ConfirmationStatus.PENDING_CONFIRMATION).build());
            assertThat(search.searchFiles("unconfirmed-tag")).doesNotContain("A_PRIVATE");
            assertThat(search.searchFiles("shared")).contains("A_PRIVATE").doesNotContain("B_SECRET");
            assertThat(search.searchFiles("secret-tag")).doesNotContain("B_SECRET");
            assertThat(search.getRecentUploads()).contains("A_PRIVATE").doesNotContain("B_SECRET");
            assertThatThrownBy(() -> search.searchFiles(b.getId(), "shared")).isInstanceOf(AccessDeniedException.class);
        });
        TenantContext.runAs(b.getId(), () -> assertThat(search.searchFiles("secret-tag")).contains("B_SECRET"));
    }

    @Test void recentUploadWindowIsOrderedAndLimitedToEight() {
        TenantContext.runAs(a.getId(), () -> {
            for (int i = 0; i < 9; i++) files.saveAndFlush(FileMetadata.builder().fileName("recent-" + i)
                    .fileSize(1L).fileType("txt").build());
            String result = search.getRecentUploads();
            assertThat(result.lines()).hasSize(8);
            assertThat(result).contains("recent-8", "recent-1").doesNotContain("recent-0", "A_PRIVATE", "B_SECRET");
            assertThat(result.indexOf("recent-8")).isLessThan(result.indexOf("recent-1"));
        });
    }

    @Test void vectorOutageAndTimeoutFallBackWithoutLosingOwner() {
        properties.setEnabled(true);
        when(vectors.searchSimilar(any(), anyInt())).thenThrow(new IllegalStateException("unavailable"));
        TenantContext.runAs(a.getId(), () -> assertThat(search.searchFiles("shared")).contains("A_PRIVATE").doesNotContain("B_SECRET"));
        properties.setVectorTimeoutMs(20);
        when(embedding.embed(anyString())).thenAnswer(i -> {
            Thread.sleep(150);
            return Response.from(Embedding.from(new float[]{1, 0}));
        });
        TenantContext.runAs(b.getId(), () -> assertThat(search.searchFiles("shared")).contains("B_SECRET").doesNotContain("A_PRIVATE"));
        properties.setVectorTimeoutMs(1500);
    }

    @Test void vectorPoisonForeignMissingOldRevisionAndPendingHitsAreDiscarded() {
        properties.setEnabled(true);
        when(vectors.searchSimilar(any(), anyInt())).thenReturn(List.of(
                new VectorSearchResult(fb.getId() + ":0:0", 1), new VectorSearchResult(fa.getId() + ":9:0", .9),
                new VectorSearchResult("9999999:0:0", .8)));
        TenantContext.runAs(a.getId(), () -> {
            assertThat(hybrid.search("nothing-matches")).isEmpty();
            when(vectors.searchSimilar(any(), anyInt())).thenReturn(List.of(new VectorSearchResult(fa.getId() + ":0:0", 1)));
            assertThat(hybrid.search("nothing-matches")).extracting(FileMetadata::getId).containsExactly(fa.getId());
            fa.setStatus(FileStatus.PENDING); files.saveAndFlush(fa);
            assertThat(hybrid.search("nothing-matches")).isEmpty();
        });
    }

    @Test void parsedContentUsesDatabaseOwnershipBeforeStorageAndCapturesRevision() {
        TenantContext.runAs(a.getId(), () -> {
            assertThat(parse.parseFile(fb.getId().toString())).isEqualTo(parse.parseFile("999999999"));
            verifyNoInteractions(storage);
            citations.begin();
            assertThat(parse.parseFile(fa.getId().toString())).isEqualTo("A_CONTENT");
            var sources = citations.finish();
            assertThat(sources).hasSize(1);
            assertThat(sources.get(0).getContentUrl()).endsWith("?revision=0");
        });
    }

    @Test void foreignUnknownAndMalformedSessionsFailBeforeAnyModelOrRedisCall() throws Exception {
        String bSession = TenantContext.callAs(b.getId(), sessions::create);
        TenantContext.runAs(a.getId(), () -> {
            for (String id : List.of(bSession, UUID.randomUUID().toString(), b.getId() + ":" + bSession))
                assertThatThrownBy(() -> conversation.sendMessageWithSources(id, "guess")).isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(model, redis);
        });
    }

    @Test void realAgentToolFramesContainOnlyAuthorizedSources() {
        when(model.chat(any(ChatRequest.class))).thenReturn(tool("search_files", "{\"keyword\":\"shared\"}"),
                tool("parse_file", "{\"id\":\"" + fb.getId() + "\"}"), reply("done"));
        TenantContext.runAs(a.getId(), () -> {
            var result = conversation.sendMessageWithSources(null, "find files");
            assertThat(result.sessionId()).isNotBlank();
            assertThat(result.citations()).extracting(c -> c.getFileId()).containsExactly(fa.getId());
            var requests = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
            verify(model, times(3)).chat(requests.capture());
            String frames = requests.getAllValues().stream().map(r -> r.messages().toString()).collect(java.util.stream.Collectors.joining());
            assertThat(frames).contains("A_PRIVATE", "文件不存在或已被删除").doesNotContain("B_SECRET", "B_CONTENT");
            verifyNoInteractions(storage);
        });
    }

    @Test void retainedMemoryAndRawRedisKeyCannotCrossOwnersOrAdmins() throws Exception {
        String session = TenantContext.callAs(a.getId(), sessions::create);
        var retained = TenantContext.callAs(a.getId(), () -> memory.getMemory(session));
        TenantContext.runAs(a.getId(), () -> retained.add(UserMessage.from("A_ONLY")));
        TenantContext.runAs(b.getId(), () -> {
            assertThatThrownBy(retained::messages).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> retained.add(UserMessage.from("poison"))).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(retained::clear).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> store.getMessages(a.getId() + ":" + session)).isInstanceOf(AccessDeniedException.class);
        });
        TenantContext.runAs(admin.getId(), () -> assertThatThrownBy(() -> memory.getMemory(session)).isInstanceOf(AccessDeniedException.class));
        TenantContext.runAs(a.getId(), () -> assertThat(retained.messages().toString()).contains("A_ONLY").doesNotContain("poison"));
    }

    @Test void changedOrDeletedSourceInvalidatesFullMemoryBeforeNextModelRequest() {
        TenantContext.runAs(a.getId(), () -> {
            when(model.chat(any(ChatRequest.class))).thenReturn(tool("parse_file", "{\"id\":\"" + fa.getId() + "\"}"), reply("A_CONTENT"));
            var first = conversation.sendMessageWithSources(null, "read file");
            assertThat(memory.getMemory(first.sessionId()).messages().toString()).contains("A_CONTENT");
            fa.setRevision(1L); files.saveAndFlush(fa);
            reset(model); when(model.chat(any(ChatRequest.class))).thenReturn(reply("fresh"));
            conversation.sendMessageWithSources(first.sessionId(), "follow up");
            var request = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
            verify(model).chat(request.capture());
            assertThat(request.getValue().messages().toString()).doesNotContain("A_CONTENT", "read file");
            citations.begin(); citations.capture(fa, 1d, "PARSE", "new");
            memory.getMemory(first.sessionId()).add(UserMessage.from("new-source")); citations.clear();
            files.deleteById(fa.getId());
            assertThat(memory.getMemory(first.sessionId()).messages()).isEmpty();
        });
    }

    @Test void anonymousAdminAndDisabledUserCannotInvokeAgentOrTools() {
        assertThatThrownBy(() -> search.getRecentUploads()).isInstanceOf(AccessDeniedException.class);
        TenantContext.runAs(admin.getId(), () -> {
            assertThatThrownBy(() -> conversation.sendMessageWithSources(null, "show all files")).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> parse.parseFile(fa.getId().toString())).isInstanceOf(AccessDeniedException.class);
        });
        a.setEnabled(false); users.saveAndFlush(a);
        TenantContext.runAs(a.getId(), () -> assertThatThrownBy(sessions::create).isInstanceOf(AccessDeniedException.class));
        verifyNoInteractions(model, storage, redis);
    }

    @Test void parallelOwnersDoNotShareMemoryOrThreadContext() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> TenantContext.supplyAs(a.getId(), () -> conversation.sendMessageWithSources(null, "A_ONLY")));
            var two = pool.submit(() -> TenantContext.supplyAs(b.getId(), () -> conversation.sendMessageWithSources(null, "B_ONLY")));
            var ar = one.get(15, TimeUnit.SECONDS); var br = two.get(15, TimeUnit.SECONDS);
            TenantContext.runAs(a.getId(), () -> assertThat(memory.getMemory(ar.sessionId()).messages().toString()).contains("A_ONLY").doesNotContain("B_ONLY"));
            TenantContext.runAs(b.getId(), () -> assertThat(memory.getMemory(br.sessionId()).messages().toString()).contains("B_ONLY").doesNotContain("A_ONLY"));
            assertThat(pool.submit(TenantContext::currentTenantId).get()).isEqualTo(0L);
        } finally { pool.shutdownNow(); }
    }

    jakarta.servlet.http.Cookie cookie(AppUser user) {
        var context = SecurityContextHolder.createEmptyContext();
        var principal = AuthPrincipal.from(user).forSession();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        var session = httpSessions.createSession(); session.setAttribute("SPRING_SECURITY_CONTEXT", context); httpSessions.save(session);
        return new jakarta.servlet.http.Cookie("COFFER_SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }
    @Test void citationHttpReadsRequireCurrentOwnerAndExactRevision() throws Exception {
        String path = "/api/files/" + fa.getId() + "/content?revision=0";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).cookie(cookie(admin))).andExpect(status().isForbidden());
        String foreign = mvc.perform(get(path).cookie(cookie(b))).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String absent = mvc.perform(get("/api/files/999999999/content?revision=0").cookie(cookie(b))).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(foreign).isEqualTo(absent);
        mvc.perform(get(path).cookie(cookie(a))).andExpect(status().isOk()).andExpect(content().string("A_CONTENT"));
        TenantContext.runAs(a.getId(), () -> { fa.setRevision(1L); files.saveAndFlush(fa); });
        mvc.perform(get(path).cookie(cookie(a))).andExpect(status().isConflict());
        mvc.perform(get("/api/files/" + fa.getId() + "?revision=0").cookie(cookie(a))).andExpect(status().isConflict());
    }

    @Test void chatHttpCreatesServerSessionAndRejectsForeignContinuation() throws Exception {
        var csrf = new jakarta.servlet.http.Cookie("XSRF-TOKEN", "r13-token");
        String response = mvc.perform(post("/api/chat/send").cookie(cookie(a), csrf).header("X-XSRF-TOKEN", "r13-token")
                .contentType("application/json").content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("data").path("sessionId").asText();
        assertThat(id).matches("[0-9a-f-]{36}");
        String body = json.writeValueAsString(Map.of("sessionId", id, "message", "guess"));
        mvc.perform(post("/api/chat/send").cookie(cookie(b), csrf).header("X-XSRF-TOKEN", "r13-token")
                .contentType("application/json").content(body)).andExpect(status().isNotFound());
        mvc.perform(post("/api/chat/send").cookie(cookie(admin), csrf).header("X-XSRF-TOKEN", "r13-token")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        verify(model, times(1)).chat(any(ChatRequest.class));
    }

    @Test void vectorGenerationKeysAndDeletionAreOwnerScoped() {
        RedisVectorStore actual = new RedisVectorStore(redis, json);
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "keyPrefix", "coffer:vector:");
        org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(actual);
        factory.addAspect(new OwnerAuthorizationAspect(authorization));
        RedisVectorStore secured = factory.getProxy();
        TenantContext.runAs(a.getId(), () -> secured.setActiveGeneration("a-generation"));
        TenantContext.runAs(b.getId(), () -> {
            assertThat(secured.activeGeneration()).isNull();
            secured.setActiveGeneration("b-generation");
            assertThatThrownBy(() -> secured.setActiveGeneration("../owner:" + a.getId())).isInstanceOf(IllegalArgumentException.class);
        });
        TenantContext.runAs(a.getId(), () -> assertThat(secured.activeGeneration()).isEqualTo("a-generation"));
        TenantContext.runAs(admin.getId(), () -> assertThatThrownBy(secured::activeGeneration).isInstanceOf(AccessDeniedException.class));
        assertThat(redisValues).containsEntry("coffer:vector:owner:" + b.getId() + ":active-generation", "b-generation");
    }
}

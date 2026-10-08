package com.coffer.operations;

import com.coffer.auth.service.AdminAuthorization;
import com.coffer.service.SecretCryptoService;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterKeyRotationTest {
    OperationsDatabase db;
    SecretCryptoService old, rotating;
    MasterKeyRotationService service;
    AdminAuthorization authorization;
    static final String SECRET = "R26_SECRET_API_KEY_BODY";
    @BeforeEach void setup() {
        db = new OperationsDatabase(); authorization = mock(AdminAuthorization.class);
        old = crypto("old-master-key", ""); rotating = crypto("new-master-key", "old-master-key");
        var target = new MasterKeyRotationService(db.jdbc, rotating, authorization);
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.dataSource), new AnnotationTransactionAttributeSource()));
        service = (MasterKeyRotationService) proxy.getProxy();
    }
    static SecretCryptoService crypto(String key, String previous) {
        var crypto = new SecretCryptoService(key, new MockEnvironment().withProperty("COFFER_SECRET_PREVIOUS_KEY", previous));
        ReflectionTestUtils.invokeMethod(crypto, "initialize"); return crypto;
    }
    void credential(long owner, String ciphertext) {
        db.jdbc.update("INSERT INTO user_model_credential(owner_id,provider,encrypted_api_key,updated_at) VALUES(?,'DEEPSEEK',?,CURRENT_TIMESTAMP)", owner, ciphertext);
    }
    void snapshot(long owner, String id, String ciphertext) {
        db.jdbc.update("INSERT INTO model_execution_snapshot(id,owner_id,configuration_version,run_mode,encrypted_configuration,confirmed_at,purpose) VALUES(?,?,'v','API',?,CURRENT_TIMESTAMP,'UPLOAD')", id, owner, ciphertext);
    }
    @Test void rotationIncludesCompositeOwnerCredentialsLegacyEndpointsAndSnapshotsAcrossPages() {
        long a = db.owner(true), b = db.owner(false);
        String cipher = old.encrypt(SECRET); credential(a, cipher); credential(b, cipher);
        db.jdbc.update("INSERT INTO model_credential(provider,encrypted_api_key,updated_at) VALUES('DEEPSEEK',?,CURRENT_TIMESTAMP)", cipher);
        db.jdbc.update("INSERT INTO model_runtime_endpoint(run_mode,capability,base_url,model_name,encrypted_api_key,updated_at) VALUES('API','CHAT','https://example.invalid','private-model',?,CURRENT_TIMESTAMP)", cipher);
        for (int i = 0; i < 205; i++) snapshot(b, String.format("%04d", i), cipher);
        assertThat(service.status().remaining()).isEqualTo(209);
        var completed = service.rotate(); assertThat(completed.remaining()).isZero(); assertThat(completed.verified()).isEqualTo(209); assertThat(completed.rewritten()).isEqualTo(209);
        SecretCryptoService retired = crypto("new-master-key", "");
        for (String table : java.util.List.of("user_model_credential", "model_credential", "model_runtime_endpoint", "model_execution_snapshot")) {
            String column = table.equals("model_execution_snapshot") ? "encrypted_configuration" : "encrypted_api_key";
            for (String result : db.jdbc.queryForList("SELECT " + column + " FROM " + table, String.class)) {
                assertThat(retired.decrypt(result)).isEqualTo(SECRET); assertThat(result).doesNotContain(SECRET);
                assertThatThrownBy(() -> old.decrypt(result)).isInstanceOf(IllegalStateException.class);
            }
        }
        assertThat(service.rotate().rewritten()).isZero(); verify(authorization, atLeast(3)).requireAdmin();
    }
    @Test void corruptCiphertextRollsBackAllRewritesAndDoesNotProduceSuccessfulEvent() {
        long a = db.owner(true); String cipher = old.encrypt(SECRET); credential(a, cipher); snapshot(a, "broken", SECRET);
        assertThatThrownBy(service::rotate).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(SECRET);
        assertThat(db.jdbc.queryForObject("SELECT encrypted_api_key FROM user_model_credential", String.class)).isEqualTo(cipher);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM secret_rotation_event", Long.class)).isZero();
    }
    @Test void mislabeledOldCiphertextIsAuthenticatedAndRewrappedBeforeRetiringOldKey() {
        long owner = db.owner(true); String forged = old.encrypt(SECRET).replace(old.currentKeyId(), rotating.currentKeyId()); credential(owner, forged);
        assertThat(service.rotate().rewritten()).isEqualTo(1);
        assertThat(crypto("new-master-key", "").decrypt(db.jdbc.queryForObject("SELECT encrypted_api_key FROM user_model_credential", String.class))).isEqualTo(SECRET);
    }
    @Test void adminAuthorizationRunsBeforeAnyReadOrRewrite() {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied")).when(authorization).requireAdmin();
        assertThatThrownBy(service::status).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(service::rotate).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM secret_rotation_event", Long.class)).isZero();
    }
}

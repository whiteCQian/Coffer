package com.coffer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCryptoServiceTest {

    private SecretCryptoService service;

    @BeforeEach
    void setUp() {
        service = new SecretCryptoService("test-master-key", new MockEnvironment());
        service.initialize();
    }

    @Test
    void encryptsAndDecryptsCredential() {
        String encrypted = service.encrypt("sk-test-123");

        assertThat(encrypted).isNotEqualTo("sk-test-123");
        assertThat(service.decrypt(encrypted)).isEqualTo("sk-test-123");
    }

    @Test
    void usesDifferentCiphertextForEachEncryption() {
        assertThat(service.encrypt("same-key")).isNotEqualTo(service.encrypt("same-key"));
    }

    @Test
    void rejectsTamperedCiphertext() {
        String encrypted = service.encrypt("sk-test-123");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法解密");
    }

    @Test void rotatesMasterKeyWithoutChangingUserCredentialAndRetiresOldKey() {
        String oldCipher = service.encrypt("ROTATION_PRIVATE_KEY");
        var rotating = new SecretCryptoService("new-master-key", new MockEnvironment().withProperty("COFFER_SECRET_PREVIOUS_KEY", "test-master-key"));
        rotating.initialize();
        assertThat(rotating.decrypt(oldCipher)).isEqualTo("ROTATION_PRIVATE_KEY");
        String newCipher = rotating.rewrap(oldCipher);
        var retired = new SecretCryptoService("new-master-key", new MockEnvironment()); retired.initialize();
        assertThat(retired.decrypt(newCipher)).isEqualTo("ROTATION_PRIVATE_KEY");
        assertThatThrownBy(() -> retired.decrypt(oldCipher)).isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThatThrownBy(() -> service.decrypt(newCipher)).isInstanceOf(IllegalStateException.class);
        assertThat(newCipher).doesNotContain("ROTATION_PRIVATE_KEY", "new-master-key");
    }
}

package com.coffer.service;

import com.coffer.entity.ModelCredential;
import com.coffer.entity.ModelProvider;
import com.coffer.repository.ModelCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCredentialServiceTest {

    private ModelCredentialRepository repository;
    private SecretCryptoService cryptoService;
    private ModelCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(ModelCredentialRepository.class);
        cryptoService = new SecretCryptoService("test-master-key", new org.springframework.mock.env.MockEnvironment());
        cryptoService.initialize();
        service = new ModelCredentialService(repository, cryptoService);
    }

    @Test
    void savesTrimmedEncryptedCredential() {
        service.save(ModelProvider.DEEPSEEK, "  sk-deepseek  ");

        var captor = org.mockito.ArgumentCaptor.forClass(ModelCredential.class);
        verify(repository).save(captor.capture());
        ModelCredential saved = captor.getValue();
        assertThat(saved.getProvider()).isEqualTo(ModelProvider.DEEPSEEK);
        assertThat(saved.getEncryptedApiKey()).isNotEqualTo("sk-deepseek");
        assertThat(cryptoService.decrypt(saved.getEncryptedApiKey())).isEqualTo("sk-deepseek");
    }

    @Test
    void readsAndDeletesCredentialByProvider() {
        String encrypted = cryptoService.encrypt("sk-qwen");
        when(repository.findById(ModelProvider.QWEN_VL))
                .thenReturn(Optional.of(ModelCredential.builder()
                        .provider(ModelProvider.QWEN_VL)
                        .encryptedApiKey(encrypted)
                        .build()));

        assertThat(service.getApiKey(ModelProvider.QWEN_VL)).isEqualTo("sk-qwen");
        assertThat(service.getMaskedApiKey(ModelProvider.QWEN_VL)).isEqualTo("*******");
        service.delete(ModelProvider.QWEN_VL);
        verify(repository).deleteById(ModelProvider.QWEN_VL);
    }

    @Test
    void rejectsBlankCredentialWithoutWriting() {
        assertThatThrownBy(() -> service.save(ModelProvider.DEEPSEEK, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("模型密钥不能为空");
    }
}

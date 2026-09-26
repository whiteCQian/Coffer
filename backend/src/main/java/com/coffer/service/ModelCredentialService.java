package com.coffer.service;

import com.coffer.entity.ModelCredential;
import com.coffer.entity.ModelProvider;
import com.coffer.repository.ModelCredentialRepository;
import com.coffer.model.runtime.ModelCredentialChangedEvent;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/** Stores and reads model API keys without exposing plaintext outside the service. */
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class ModelCredentialService {

    private final ModelCredentialRepository repository;
    private final SecretCryptoService cryptoService;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    public boolean isConfigured(ModelProvider provider) {
        return repository.existsById(provider);
    }

    public String getApiKey(ModelProvider provider) {
        return repository.findById(provider)
                .map(ModelCredential::getEncryptedApiKey)
                .map(cryptoService::decrypt)
                .orElse("");
    }

    public String getMaskedApiKey(ModelProvider provider) {
        String apiKey = getApiKey(provider);
        if (apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= 7) {
            return "*".repeat(apiKey.length());
        }
        return apiKey.substring(0, 3) + "*".repeat(Math.max(4, apiKey.length() - 7))
                + apiKey.substring(apiKey.length() - 4);
    }

    @Transactional
    public void save(ModelProvider provider, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("模型密钥不能为空");
        }
        ModelCredential credential = repository.findById(provider)
                .orElseGet(() -> ModelCredential.builder().provider(provider).build());
        credential.setEncryptedApiKey(cryptoService.encrypt(apiKey.trim()));
        credential.setUpdatedAt(LocalDateTime.now());
        repository.save(credential);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ModelCredentialChangedEvent(provider));
        }
    }

    @Transactional
    public void delete(ModelProvider provider) {
        repository.deleteById(provider);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ModelCredentialChangedEvent(provider));
        }
    }

    public Map<ModelProvider, Boolean> status() {
        Map<ModelProvider, Boolean> result = new EnumMap<>(ModelProvider.class);
        for (ModelProvider provider : ModelProvider.values()) {
            result.put(provider, isConfigured(provider));
        }
        return result;
    }
}

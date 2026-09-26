package com.coffer.service;

import com.coffer.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Re-encrypt only the caller's secrets; changing encryption does not change task destinations. */
@Service @com.coffer.auth.service.OwnerOnly @RequiredArgsConstructor
public class UserSecretRotationService {
    private final SecretCryptoService crypto;
    private final ModelCredentialRepository credentials;
    private final ModelRuntimeEndpointRepository endpoints;
    private final ModelExecutionSnapshotRepository snapshots;
    @Transactional
    public void rotate() {
        credentials.findAll().forEach(row -> {
            row.setEncryptedApiKey(crypto.rewrap(row.getEncryptedApiKey())); credentials.save(row);
        });
        endpoints.findAll().forEach(row -> {
            if (row.getEncryptedApiKey() != null && !row.getEncryptedApiKey().isBlank()) {
                row.setEncryptedApiKey(crypto.rewrap(row.getEncryptedApiKey())); endpoints.save(row);
            }
        });
        snapshots.findAll().forEach(row -> {
            row.setEncryptedConfiguration(crypto.rewrap(row.getEncryptedConfiguration())); snapshots.save(row);
        });
    }
}

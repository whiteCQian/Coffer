package com.coffer.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM encryption for secrets persisted in the database. */
@Service
public class SecretCryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private static final String DEV_FALLBACK = "coffer-development-master-key-change-me";

    private final String configuredMasterKey;
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKeySpec secretKey;
    private String keyId;
    private final java.util.List<SecretKeySpec> decryptionKeys = new java.util.ArrayList<>();

    public SecretCryptoService(
            @Value("${COFFER_SECRET_KEY:}") String configuredMasterKey,
            Environment environment) {
        this.configuredMasterKey = configuredMasterKey;
        this.environment = environment;
    }

    @PostConstruct
    void initialize() {
        String masterKey = configuredMasterKey;
        if (masterKey == null || masterKey.isBlank()) {
            if (environment.matchesProfiles("prod")) {
                throw new IllegalStateException("COFFER_SECRET_KEY must be set when the prod profile is active");
            }
            masterKey = DEV_FALLBACK;
        }
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(masterKey.getBytes(StandardCharsets.UTF_8));
            secretKey = new SecretKeySpec(key, "AES");
            keyId = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key)).substring(0, 16);
            decryptionKeys.add(secretKey);
            String previous = environment.getProperty("COFFER_SECRET_PREVIOUS_KEY", "");
            if (!previous.isBlank()) decryptionKeys.add(new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(previous.getBytes(StandardCharsets.UTF_8)), "AES"));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize secret encryption", e);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return "v1:" + keyId + ":" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt model credential", e);
        }
    }

    public String decrypt(String encrypted) {
        for (SecretKeySpec key : decryptionKeys) {
            try { return decryptWith(encrypted, key); }
            catch (Exception ignored) { }
        }
        throw new IllegalStateException("无法解密凭据，请检查加密主密钥配置");
    }

    public String rewrap(String encrypted) { return encrypt(decrypt(encrypted)); }

    private String decryptWith(String encrypted, SecretKeySpec key) throws Exception {
        try {
            if (encrypted.startsWith("v1:")) encrypted = encrypted.split(":", 3)[2];
            byte[] combined = Base64.getDecoder().decode(encrypted);
            byte[] iv = java.util.Arrays.copyOfRange(combined, 0, IV_LENGTH);
            byte[] ciphertext = java.util.Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decrypt model credential; check COFFER_SECRET_KEY", e);
        }
    }
}

package com.tejas.postgresinstancemanager.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class CredentialEncryptionService {

    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final byte[] AAD_PREFIX = "pim-db-credential:".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialEncryptionService(@Value("${app.security.credential-encryption-key}") String encodedKey) {
        final byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("CREDENTIAL_ENCRYPTION_KEY must be Base64 encoded.", exception);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException("CREDENTIAL_ENCRYPTION_KEY must contain exactly 32 random bytes.");
        }
        encryptionKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(UUID instanceId, String credential) {
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(associatedData(instanceId));
            byte[] encrypted = cipher.doFinal(credential.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce)
                    .put(encrypted)
                    .array());
        } catch (GeneralSecurityException exception) {
            throw new CredentialEncryptionException("Could not encrypt the database credential.", exception);
        }
    }

    public String decrypt(UUID instanceId, String ciphertext) {
        final byte[] payload;
        try {
            payload = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException exception) {
            throw new CredentialEncryptionException("The stored database credential is invalid.", exception);
        }
        if (payload.length <= NONCE_LENGTH_BYTES) {
            throw new CredentialEncryptionException("The stored database credential is incomplete.");
        }

        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        byte[] encrypted = new byte[payload.length - NONCE_LENGTH_BYTES];
        ByteBuffer.wrap(payload).get(nonce).get(encrypted);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(associatedData(instanceId));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new CredentialEncryptionException("Could not decrypt the stored database credential.", exception);
        }
    }

    private byte[] associatedData(UUID instanceId) {
        return ByteBuffer.allocate(AAD_PREFIX.length + 36)
                .put(AAD_PREFIX)
                .put(instanceId.toString().getBytes(StandardCharsets.UTF_8))
                .array();
    }
}

package com.tejas.postgresinstancemanager;

import com.tejas.postgresinstancemanager.security.CredentialEncryptionException;
import com.tejas.postgresinstancemanager.security.CredentialEncryptionService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialEncryptionServiceTests {

    private static final String ENCRYPTION_KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";

    private final CredentialEncryptionService encryptionService =
            new CredentialEncryptionService(ENCRYPTION_KEY);

    @Test
    void encryptsWithUniqueNoncesAndBindsCiphertextToItsInstance() {
        UUID instanceId = UUID.randomUUID();
        String credential = "database-password";
        String firstCiphertext = encryptionService.encrypt(instanceId, credential);
        String secondCiphertext = encryptionService.encrypt(instanceId, credential);

        assertThat(firstCiphertext).isNotEqualTo(secondCiphertext).doesNotContain(credential);
        assertThat(encryptionService.decrypt(instanceId, firstCiphertext)).isEqualTo(credential);
        assertThatThrownBy(() -> encryptionService.decrypt(UUID.randomUUID(), firstCiphertext))
                .isInstanceOf(CredentialEncryptionException.class);
    }

    @Test
    void rejectsShortEncryptionKeys() {
        assertThatThrownBy(() -> new CredentialEncryptionService("c2hvcnQ="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 random bytes");
    }
}

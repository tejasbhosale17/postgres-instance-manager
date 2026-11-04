package com.tejas.postgresinstancemanager.security;

public class CredentialEncryptionException extends RuntimeException {

    public CredentialEncryptionException(String message, Throwable cause) {
        super(message, cause);
    }

    public CredentialEncryptionException(String message) {
        super(message);
    }
}

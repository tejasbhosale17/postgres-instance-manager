package com.tejas.postgresinstancemanager.worker;

public class PostgresProvisioningException extends RuntimeException {

    private final String errorCode;

    public PostgresProvisioningException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    public PostgresProvisioningException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

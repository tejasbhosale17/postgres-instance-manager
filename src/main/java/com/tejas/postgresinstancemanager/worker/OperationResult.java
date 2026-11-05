package com.tejas.postgresinstancemanager.worker;

public record OperationResult(
        String containerId,
        String volumeName,
        Integer hostPort) {

    public static OperationResult empty() {
        return new OperationResult(null, null, null);
    }

    public static OperationResult provisioned(ProvisionedPostgres postgres) {
        return new OperationResult(postgres.containerId(), postgres.volumeName(), postgres.hostPort());
    }

    public static OperationResult withHostPort(int hostPort) {
        return new OperationResult(null, null, hostPort);
    }
}

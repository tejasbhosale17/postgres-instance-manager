package com.tejas.postgresinstancemanager.worker;

public record ProvisionedPostgres(String containerId, String volumeName, int hostPort) {
}

package com.tejas.postgresinstancemanager.worker;

public record ManagedPostgresStatus(boolean running, int hostPort) {
}

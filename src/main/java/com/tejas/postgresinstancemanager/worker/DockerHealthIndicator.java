package com.tejas.postgresinstancemanager.worker;

import com.github.dockerjava.api.DockerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class DockerHealthIndicator implements HealthIndicator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerHealthIndicator.class);
    private final DockerClient dockerClient;

    public DockerHealthIndicator(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public Health health() {
        try {
            dockerClient.pingCmd().exec();
            return Health.up().build();
        } catch (RuntimeException exception) {
            LOGGER.warn("Docker health check failed: {}", exception.getClass().getSimpleName());
            return Health.down().build();
        }
    }
}

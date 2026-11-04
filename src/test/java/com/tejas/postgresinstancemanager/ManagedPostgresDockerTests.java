package com.tejas.postgresinstancemanager;

import com.tejas.postgresinstancemanager.worker.ProvisionedPostgres;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedPostgresDockerTests {

    @Test
    void testImplementationReusesInstanceResources() {
        FakeManagedPostgresDocker docker = new FakeManagedPostgresDocker();
        UUID instanceId = UUID.randomUUID();

        ProvisionedPostgres first = docker.provision(instanceId, "not-stored-by-the-fake");
        ProvisionedPostgres retry = docker.provision(instanceId, "another-password");

        assertThat(retry).isEqualTo(first);
        assertThat(docker.createdCount()).isEqualTo(1);
    }
}

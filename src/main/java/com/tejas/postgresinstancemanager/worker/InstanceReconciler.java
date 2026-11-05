package com.tejas.postgresinstancemanager.worker;

import com.tejas.postgresinstancemanager.instances.InstanceRecord;
import com.tejas.postgresinstancemanager.instances.InstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InstanceReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstanceReconciler.class);

    private final InstanceRepository instanceRepository;
    private final ManagedPostgresDocker managedPostgresDocker;

    public InstanceReconciler(
            InstanceRepository instanceRepository,
            ManagedPostgresDocker managedPostgresDocker) {
        this.instanceRepository = instanceRepository;
        this.managedPostgresDocker = managedPostgresDocker;
    }

    @Scheduled(fixedDelayString = "${app.worker.reconcile-delay-ms:60000}")
    public void reconcileInstances() {
        for (InstanceRecord instance : instanceRepository.findStableInstancesForReconciliation()) {
            try {
                if (instance.hostPort() == null) {
                    LOGGER.error("Stable instance {} has no recorded host port.", instance.id());
                    instanceRepository.markMissingManagedContainer(instance.id());
                    continue;
                }
                var dockerState = managedPostgresDocker.inspect(instance.id(), instance.hostPort());
                if (dockerState.isEmpty()) {
                    LOGGER.error("Managed PostgreSQL container for instance {} is missing.", instance.id());
                    instanceRepository.markMissingManagedContainer(instance.id());
                    continue;
                }
                String actualState = dockerState.get().running() ? "READY" : "STOPPED";
                if (!actualState.equals(instance.state())
                        || !Integer.valueOf(dockerState.get().hostPort()).equals(instance.hostPort())) {
                    LOGGER.warn("Reconciling instance {} from state {} to {}.",
                            instance.id(), instance.state(), actualState);
                    instanceRepository.reconcileState(
                            instance.id(), actualState, dockerState.get().hostPort());
                }
            } catch (PostgresProvisioningException exception) {
                LOGGER.warn("Could not reconcile instance {}: {}",
                        instance.id(), exception.errorCode());
                if ("MANAGED_VOLUME_NOT_FOUND".equals(exception.errorCode())) {
                    instanceRepository.markMissingManagedContainer(instance.id());
                }
            }
        }
    }
}

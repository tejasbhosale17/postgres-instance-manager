package com.tejas.postgresinstancemanager.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public class OperationMetrics implements MeterBinder {

    private static final String[] STATES = {"QUEUED", "RUNNING", "RETRY_WAIT", "SUCCEEDED", "FAILED"};
    private final OperationRepository operationRepository;

    public OperationMetrics(OperationRepository operationRepository) {
        this.operationRepository = operationRepository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String state : STATES) {
            Gauge.builder("pim.operations.in_state", operationRepository, repository -> repository.countByState(state))
                    .tag("state", state.toLowerCase())
                    .register(registry);
        }
    }
}

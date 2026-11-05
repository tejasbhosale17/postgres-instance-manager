package com.tejas.postgresinstancemanager.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditEventService {

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    public AuditEventService(AuditEventRepository auditEventRepository, ObjectMapper objectMapper) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
    }

    public void record(
            UUID actorId,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, String> details,
            String correlationId) {
        try {
            auditEventRepository.record(
                    actorId,
                    action,
                    resourceType,
                    resourceId,
                    objectMapper.writeValueAsString(details),
                    correlationId);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize audit details.", exception);
        }
    }
}

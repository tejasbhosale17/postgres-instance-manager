package com.tejas.postgresinstancemanager.instances;

import com.tejas.postgresinstancemanager.api.ResourceNotFoundException;
import com.tejas.postgresinstancemanager.config.CorrelationIdFilter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/instances")
public class InstanceController {

    private final InstanceRepository instanceRepository;
    private final InstanceService instanceService;

    public InstanceController(InstanceRepository instanceRepository, InstanceService instanceService) {
        this.instanceRepository = instanceRepository;
        this.instanceService = instanceService;
    }

    @PostMapping
    public ResponseEntity<OperationAccepted> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateInstanceRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE_NAME) String correlationId) {
        OperationAccepted accepted = instanceService.requestProvisioning(
                UUID.fromString(jwt.getSubject()),
                request,
                idempotencyKey,
                correlationId);
        return ResponseEntity.accepted().body(accepted);
    }

    @GetMapping
    public List<InstanceRecord> list(@AuthenticationPrincipal Jwt jwt) {
        if (isAdministrator(jwt)) {
            return instanceRepository.findAll();
        }
        return instanceRepository.findByOwnerId(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/{instanceId}")
    public InstanceRecord get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID instanceId) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        return (isAdministrator(jwt)
                ? instanceRepository.findById(instanceId)
                : instanceRepository.findByIdAndOwnerId(instanceId, ownerId))
                .orElseThrow(ResourceNotFoundException::new);
    }

    @PostMapping("/{instanceId}/start")
    public ResponseEntity<OperationAccepted> start(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID instanceId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE_NAME) String correlationId) {
        return accepted(instanceService.requestStart(
                UUID.fromString(jwt.getSubject()), isAdministrator(jwt), instanceId, idempotencyKey, correlationId));
    }

    @PostMapping("/{instanceId}/stop")
    public ResponseEntity<OperationAccepted> stop(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID instanceId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE_NAME) String correlationId) {
        return accepted(instanceService.requestStop(
                UUID.fromString(jwt.getSubject()), isAdministrator(jwt), instanceId, idempotencyKey, correlationId));
    }

    @PostMapping("/{instanceId}/restart")
    public ResponseEntity<OperationAccepted> restart(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID instanceId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE_NAME) String correlationId) {
        return accepted(instanceService.requestRestart(
                UUID.fromString(jwt.getSubject()), isAdministrator(jwt), instanceId, idempotencyKey, correlationId));
    }

    @DeleteMapping("/{instanceId}")
    public ResponseEntity<OperationAccepted> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID instanceId,
            @Valid @RequestBody DeleteInstanceRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE_NAME) String correlationId) {
        return accepted(instanceService.requestDelete(
                UUID.fromString(jwt.getSubject()), isAdministrator(jwt), instanceId, idempotencyKey, correlationId));
    }

    private ResponseEntity<OperationAccepted> accepted(OperationAccepted operation) {
        return ResponseEntity.accepted().body(operation);
    }

    private boolean isAdministrator(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMINISTRATOR");
    }
}

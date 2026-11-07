package com.tejas.postgresinstancemanager.backups;

import com.tejas.postgresinstancemanager.api.ResourceNotFoundException;
import com.tejas.postgresinstancemanager.config.CorrelationIdFilter;
import com.tejas.postgresinstancemanager.instances.InstanceService;
import com.tejas.postgresinstancemanager.instances.InstanceStateConflictException;
import com.tejas.postgresinstancemanager.instances.OperationAccepted;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Validated
public class BackupController {

    private final BackupRepository backupRepository;
    private final BackupFileStore backupFileStore;
    private final InstanceService instanceService;

    public BackupController(
            BackupRepository backupRepository,
            BackupFileStore backupFileStore,
            InstanceService instanceService) {
        this.backupRepository = backupRepository;
        this.backupFileStore = backupFileStore;
        this.instanceService = instanceService;
    }

    @PostMapping("/instances/{instanceId}/backups")
    public ResponseEntity<OperationAccepted> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID instanceId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE_NAME) String correlationId) {
        return ResponseEntity.accepted().body(instanceService.requestBackup(
                userId(jwt), isAdministrator(jwt), instanceId, idempotencyKey, correlationId));
    }

    @PostMapping("/instances/{instanceId}/restore")
    public ResponseEntity<OperationAccepted> restore(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID instanceId,
            @Valid @RequestBody RestoreRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE_NAME) String correlationId) {
        return ResponseEntity.accepted().body(instanceService.requestRestore(
                userId(jwt),
                isAdministrator(jwt),
                instanceId,
                request.backupId(),
                request.confirmDataReplacement(),
                idempotencyKey,
                correlationId));
    }

    @GetMapping("/backups")
    public List<BackupRecord> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return isAdministrator(jwt)
                ? backupRepository.findAll(limit)
                : backupRepository.findByOwnerId(userId(jwt), limit);
    }

    @GetMapping("/backups/{backupId}")
    public BackupRecord get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID backupId) {
        return findAccessible(jwt, backupId);
    }

    @GetMapping("/backups/{backupId}/content")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID backupId) {
        BackupRecord backup = findAccessible(jwt, backupId);
        if (!"READY".equals(backup.state())) {
            throw new InstanceStateConflictException();
        }
        Resource resource = backupFileStore.resource(backup);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(backup.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("postgres-backup-" + backup.id() + ".dump")
                        .build()
                        .toString())
                .body(resource);
    }

    private BackupRecord findAccessible(Jwt jwt, UUID backupId) {
        return (isAdministrator(jwt)
                ? backupRepository.findById(backupId)
                : backupRepository.findByIdAndOwnerId(backupId, userId(jwt)))
                .orElseThrow(ResourceNotFoundException::new);
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private boolean isAdministrator(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMINISTRATOR");
    }
}

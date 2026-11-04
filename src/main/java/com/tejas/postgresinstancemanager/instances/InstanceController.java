package com.tejas.postgresinstancemanager.instances;

import com.tejas.postgresinstancemanager.api.ResourceNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            @Valid @RequestBody CreateInstanceRequest request) {
        OperationAccepted accepted = instanceService.requestProvisioning(
                UUID.fromString(jwt.getSubject()),
                request);
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

    private boolean isAdministrator(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMINISTRATOR");
    }
}

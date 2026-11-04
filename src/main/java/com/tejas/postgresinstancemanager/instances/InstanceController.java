package com.tejas.postgresinstancemanager.instances;

import com.tejas.postgresinstancemanager.api.ResourceNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    public InstanceController(InstanceRepository instanceRepository) {
        this.instanceRepository = instanceRepository;
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

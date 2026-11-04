package com.tejas.postgresinstancemanager.operations;

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
@RequestMapping("/api/operations")
public class OperationController {

    private final OperationRepository operationRepository;

    public OperationController(OperationRepository operationRepository) {
        this.operationRepository = operationRepository;
    }

    @GetMapping("/{operationId}")
    public OperationRecord get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID operationId) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        List<String> roles = jwt.getClaimAsStringList("roles");
        boolean administrator = roles != null && roles.contains("ADMINISTRATOR");
        return (administrator
                ? operationRepository.findById(operationId)
                : operationRepository.findByIdAndOwnerId(operationId, ownerId))
                .orElseThrow(ResourceNotFoundException::new);
    }
}

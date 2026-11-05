package com.tejas.postgresinstancemanager.audit;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/activity")
@Validated
public class ActivityController {

    private final AuditEventRepository auditEventRepository;

    public ActivityController(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @GetMapping
    public List<ActivityRecord> activity(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        List<String> roles = jwt.getClaimAsStringList("roles");
        boolean administrator = roles != null && roles.contains("ADMINISTRATOR");
        return auditEventRepository.findActivity(ownerId, administrator, limit);
    }
}

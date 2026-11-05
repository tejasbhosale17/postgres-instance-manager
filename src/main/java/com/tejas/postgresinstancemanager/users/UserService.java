package com.tejas.postgresinstancemanager.users;

import com.tejas.postgresinstancemanager.api.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UserSummary registerViewer(String username, String rawPassword) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new UsernameAlreadyExistsException();
        }
        try {
            UserAccount account = userRepository.create(
                    username,
                    passwordEncoder.encode(rawPassword),
                    UserRole.VIEWER);
            return account.summary();
        } catch (DuplicateKeyException exception) {
            throw new UsernameAlreadyExistsException();
        }
    }

    @Transactional
    public UserSummary createBootstrapAdministrator(String username, String rawPassword) {
        var existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            if (existing.get().role() != UserRole.ADMINISTRATOR) {
                throw new IllegalStateException(
                        "The configured bootstrap username already belongs to a non-administrator account.");
            }
            return existing.get().summary();
        }

        UserAccount administrator = userRepository.create(
                username,
                passwordEncoder.encode(rawPassword),
                UserRole.ADMINISTRATOR);
        userRepository.recordAuditEvent(
                null,
                "BOOTSTRAP_ADMIN_CREATED",
                "USER",
                administrator.id(),
                auditDetails(Map.of("username", username)),
                null);
        return administrator.summary();
    }

    @Transactional
    public UserSummary changeRole(
            UUID actorId,
            UUID targetUserId,
            UserRole newRole,
            String correlationId) {
        if (actorId.equals(targetUserId)) {
            throw new SelfRoleChangeException();
        }
        userRepository.lockUsersForRoleChange();
        UserAccount target = userRepository.findById(targetUserId)
                .orElseThrow(ResourceNotFoundException::new);
        if (target.role() == UserRole.ADMINISTRATOR
                && newRole != UserRole.ADMINISTRATOR
                && userRepository.countAdministrators() <= 1) {
            throw new LastAdministratorException();
        }
        userRepository.updateRole(targetUserId, newRole);
        userRepository.recordAuditEvent(
                actorId,
                "USER_ROLE_CHANGED",
                "USER",
                targetUserId,
                auditDetails(Map.of("previousRole", target.role().name(), "newRole", newRole.name())),
                correlationId);
        return userRepository.findById(targetUserId)
                .orElseThrow(ResourceNotFoundException::new)
                .summary();
    }

    private String auditDetails(Map<String, String> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize audit details.", exception);
        }
    }
}

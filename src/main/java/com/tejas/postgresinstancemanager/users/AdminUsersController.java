package com.tejas.postgresinstancemanager.users;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@Validated
public class AdminUsersController {

    private final UserRepository userRepository;
    private final UserService userService;

    public AdminUsersController(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @GetMapping
    public List<UserSummary> listUsers() {
        return userRepository.findAllSummaries();
    }

    @PatchMapping("/{userId}/role")
    @ResponseStatus(HttpStatus.OK)
    public UserSummary changeRole(
            @AuthenticationPrincipal Jwt actor,
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeRoleRequest request) {
        return userService.changeRole(UUID.fromString(actor.getSubject()), userId, request.role());
    }
}

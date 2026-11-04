package com.tejas.postgresinstancemanager.users;

import com.tejas.postgresinstancemanager.api.ResourceNotFoundException;
import com.tejas.postgresinstancemanager.security.JwtTokenService;
import com.tejas.postgresinstancemanager.security.PlatformUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthenticationController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenService jwtTokenService;

    public AuthenticationController(
            UserService userService,
            UserRepository userRepository,
            AuthenticationManager authenticationManager,
            JwtTokenService jwtTokenService) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.authenticationManager = authenticationManager;
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserSummary register(@Valid @RequestBody RegisterRequest request) {
        return userService.registerViewer(request.username(), request.password());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        final PlatformUserDetails user;
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
            user = (PlatformUserDetails) authentication.getPrincipal();
        } catch (AuthenticationException exception) {
            throw new InvalidCredentialsException();
        }
        return jwtTokenService.issue(user);
    }

    @GetMapping("/me")
    public UserSummary currentUser(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return userRepository.findById(userId)
                .orElseThrow(ResourceNotFoundException::new)
                .summary();
    }
}

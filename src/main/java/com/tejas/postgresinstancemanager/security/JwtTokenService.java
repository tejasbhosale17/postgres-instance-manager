package com.tejas.postgresinstancemanager.security;

import com.tejas.postgresinstancemanager.users.TokenResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class JwtTokenService {

    private static final long TOKEN_LIFETIME_SECONDS = 900;

    private final JwtEncoder jwtEncoder;

    public JwtTokenService(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    public TokenResponse issue(PlatformUserDetails user) {
        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("postgres-instance-manager")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(TOKEN_LIFETIME_SECONDS))
                .subject(user.id().toString())
                .claim("username", user.username())
                .claim("roles", List.of(user.role()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse("Bearer", token, TOKEN_LIFETIME_SECONDS);
    }
}

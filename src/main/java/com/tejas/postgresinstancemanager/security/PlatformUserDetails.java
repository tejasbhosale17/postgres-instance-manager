package com.tejas.postgresinstancemanager.security;

import com.tejas.postgresinstancemanager.users.UserAccount;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record PlatformUserDetails(
        UUID id,
        String username,
        String passwordHash,
        String role,
        boolean enabled) implements UserDetails {

    public static PlatformUserDetails from(UserAccount account) {
        return new PlatformUserDetails(
                account.id(),
                account.username(),
                account.passwordHash(),
                account.role().name(),
                account.enabled());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}

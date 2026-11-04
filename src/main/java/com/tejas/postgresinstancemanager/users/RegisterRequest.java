package com.tejas.postgresinstancemanager.users;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(max = 80)
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "must contain only letters, numbers, '.', '_' or '-'")
        String username,
        @NotBlank
        @Size(min = 12, max = 128)
        String password) {
}

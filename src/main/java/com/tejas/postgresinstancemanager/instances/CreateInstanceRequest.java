package com.tejas.postgresinstancemanager.instances;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateInstanceRequest(
        @NotBlank
        @Size(max = 63)
        @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9 ._-]*$")
        String name,
        @NotBlank @Size(min = 12, max = 128) String databasePassword) {
}

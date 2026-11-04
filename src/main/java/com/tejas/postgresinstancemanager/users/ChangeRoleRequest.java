package com.tejas.postgresinstancemanager.users;

import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(@NotNull UserRole role) {
}

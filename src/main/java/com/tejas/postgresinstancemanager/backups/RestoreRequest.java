package com.tejas.postgresinstancemanager.backups;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RestoreRequest(
        @NotNull UUID backupId,
        @AssertTrue(message = "must be true to replace the target instance database")
        boolean confirmDataReplacement) {
}

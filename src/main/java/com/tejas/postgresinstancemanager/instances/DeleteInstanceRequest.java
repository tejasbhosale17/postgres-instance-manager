package com.tejas.postgresinstancemanager.instances;

import jakarta.validation.constraints.AssertTrue;

public record DeleteInstanceRequest(
        @AssertTrue(message = "must be true to permanently delete the instance data")
        boolean confirmDataDeletion) {
}

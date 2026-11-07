package com.tejas.postgresinstancemanager.api;

import com.tejas.postgresinstancemanager.users.InvalidCredentialsException;
import com.tejas.postgresinstancemanager.users.LastAdministratorException;
import com.tejas.postgresinstancemanager.users.SelfRoleChangeException;
import com.tejas.postgresinstancemanager.users.UsernameAlreadyExistsException;
import com.tejas.postgresinstancemanager.instances.InstanceNameAlreadyExistsException;
import com.tejas.postgresinstancemanager.instances.InstanceStateConflictException;
import com.tejas.postgresinstancemanager.instances.IdempotencyKeyConflictException;
import com.tejas.postgresinstancemanager.instances.InvalidIdempotencyKeyException;
import com.tejas.postgresinstancemanager.instances.InvalidDataReplacementConfirmationException;
import com.tejas.postgresinstancemanager.backups.BackupArtifactUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.MDC;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid.");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleMalformedRequest() {
        return response(HttpStatus.BAD_REQUEST, "Invalid request", "The request body is malformed or contains an invalid value.");
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    ResponseEntity<ProblemDetail> handleDuplicateUsername() {
        return response(HttpStatus.CONFLICT, "Conflict", "The username is already in use.");
    }

    @ExceptionHandler(InstanceNameAlreadyExistsException.class)
    ResponseEntity<ProblemDetail> handleDuplicateInstanceName() {
        return response(HttpStatus.CONFLICT, "Conflict", "An instance with this name already exists for the owner.");
    }

    @ExceptionHandler(InstanceStateConflictException.class)
    ResponseEntity<ProblemDetail> handleInstanceStateConflict() {
        return response(HttpStatus.CONFLICT, "Conflict", "The requested operation is not valid for the instance's current state.");
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyConflict() {
        return response(HttpStatus.CONFLICT, "Conflict", "The Idempotency-Key was already used for a different request.");
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ResponseEntity<ProblemDetail> handleInvalidIdempotencyKey() {
        return response(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key",
                "The Idempotency-Key must contain 1 to 255 nonblank characters.");
    }

    @ExceptionHandler(InvalidDataReplacementConfirmationException.class)
    ResponseEntity<ProblemDetail> handleInvalidDataReplacementConfirmation() {
        return response(HttpStatus.BAD_REQUEST, "Confirmation required",
                "Set confirmDataReplacement to true to replace the target instance database.");
    }

    @ExceptionHandler(BackupArtifactUnavailableException.class)
    ResponseEntity<ProblemDetail> handleBackupArtifactUnavailable() {
        return response(HttpStatus.GONE, "Backup unavailable",
                "The backup artifact is missing or failed its integrity check.");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> handleInvalidCredentials() {
        return response(HttpStatus.UNAUTHORIZED, "Unauthorized", "The username or password is incorrect.");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ProblemDetail> handleResourceNotFound() {
        return response(HttpStatus.NOT_FOUND, "Not found", "The requested resource was not found.");
    }

    @ExceptionHandler(SelfRoleChangeException.class)
    ResponseEntity<ProblemDetail> handleSelfRoleChange() {
        return response(HttpStatus.CONFLICT, "Conflict", "Administrators cannot change their own role.");
    }

    @ExceptionHandler(LastAdministratorException.class)
    ResponseEntity<ProblemDetail> handleLastAdministrator() {
        return response(HttpStatus.CONFLICT, "Conflict", "The last enabled administrator cannot be demoted.");
    }

    private ResponseEntity<ProblemDetail> response(HttpStatus status, String title, String detail) {
        return ResponseEntity.status(status).body(problem(status, title, detail));
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("about:blank"));
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}

package com.jvmobservatory.api.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.NoSuchElementException;

/**
 * Global exception handler for the Metrics API.
 *
 * <p>Returns RFC 9457 Problem Detail responses for all errors. This provides
 * a consistent, machine-readable error format that the React dashboard can
 * parse uniformly regardless of the error type.</p>
 *
 * <p>Why RFC 9457 (ProblemDetail)?</p>
 * <ul>
 *   <li>Standardized error format — no custom error DTOs to maintain</li>
 *   <li>Built into Spring 6+ — no additional dependencies</li>
 *   <li>Includes type URI, title, status, and detail fields</li>
 *   <li>Extensible with custom properties if needed later</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles invalid request parameters (e.g., negative topN, missing appId).
     *
     * @return 400 Bad Request with problem detail
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        log.warn("[jvmobs] Bad request: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Request Parameter");
        problem.setType(URI.create("https://jvm-observatory.dev/errors/bad-request"));
        return problem;
    }

    /**
     * Handles requests for non-existent resources (e.g., unknown appId).
     *
     * @return 404 Not Found with problem detail
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        log.warn("[jvmobs] Resource not found: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource Not Found");
        problem.setType(URI.create("https://jvm-observatory.dev/errors/not-found"));
        return problem;
    }

    /**
     * Catch-all handler for unexpected errors.
     *
     * <p>Logs the full stack trace for debugging but returns a generic message
     * to the client to avoid leaking internal details.</p>
     *
     * @return 500 Internal Server Error with problem detail
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleInternalError(Exception ex) {
        log.error("[jvmobs] Unexpected error", ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please check server logs for details.");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("https://jvm-observatory.dev/errors/internal"));
        return problem;
    }
}

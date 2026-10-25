package com.rebuy.urlshortener.unit.exception;

import com.rebuy.urlshortener.exception.ApiExceptionHandler;
import com.rebuy.urlshortener.exception.InvalidUrlException;
import com.rebuy.urlshortener.exception.ShortCodeAllocationException;
import com.rebuy.urlshortener.exception.ShortLinkNotFoundException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks public error responses directly without loading Spring MVC or persistence. */
@Tag("unit")
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    /** Invalid destinations are client errors with a useful validation message. */
    @Test
    void reportsInvalidUrlAsBadRequest() {
        ProblemDetail response = handler.handleInvalidUrl(new InvalidUrlException());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getDetail())
                .isEqualTo("URL must be an absolute HTTP or HTTPS URL with a valid host and port.");
    }

    /** Unknown public codes are HTTP 404 errors at the API boundary. */
    @Test
    void reportsMissingLinkAsNotFound() {
        ProblemDetail response = handler.handleNotFound(new ShortLinkNotFoundException());

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getDetail()).isEqualTo("Short link not found");
    }

    /** Allocation exhaustion is retryable without exposing the underlying database failure. */
    @Test
    void reportsAllocationFailureWithoutDatabaseDetails() {
        IllegalStateException databaseFailure = new IllegalStateException(
                "duplicate key violates uk_short_links_code: INSERT INTO short_links");
        ShortCodeAllocationException exception = new ShortCodeAllocationException(databaseFailure);

        ProblemDetail response = handler.handleCodeAllocation(exception);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getDetail()).isEqualTo("Could not allocate a short code. Please try again.");
        assertThat(response.getProperties()).isNullOrEmpty();
    }
}

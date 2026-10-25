package com.rebuy.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Returns problem details for API failures, including Spring MVC validation errors. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * Explains why the destination cannot be shortened.
     *
     * @param exception URL validation failure
     * @return HTTP 400 problem details
     */
    @ExceptionHandler(InvalidUrlException.class)
    public ProblemDetail handleInvalidUrl(InvalidUrlException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * Reports that the requested public code has no stored destination.
     *
     * @param exception short-link lookup failure
     * @return HTTP 404 problem details
     */
    @ExceptionHandler(ShortLinkNotFoundException.class)
    public ProblemDetail handleNotFound(ShortLinkNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * Reports bounded retry exhaustion without returning database details to clients.
     *
     * @param exception failure to allocate a free code
     * @return HTTP 503 problem details allowing the client to retry later
     */
    @ExceptionHandler(ShortCodeAllocationException.class)
    public ProblemDetail handleCodeAllocation(ShortCodeAllocationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
    }
}

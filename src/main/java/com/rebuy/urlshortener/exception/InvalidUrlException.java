package com.rebuy.urlshortener.exception;

/** Indicates that the supplied destination cannot be used as an HTTP redirect URL. */
public class InvalidUrlException extends RuntimeException {

    /** Creates a validation failure with a message suitable for an API response. */
    public InvalidUrlException() {
        this("URL must be an absolute HTTP or HTTPS URL with a valid host and port.");
    }

    /**
     * Creates a validation failure explaining the rejected destination.
     *
     * @param message client-facing validation detail
     */
    public InvalidUrlException(String message) {
        super(message);
    }
}

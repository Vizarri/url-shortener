package com.rebuy.urlshortener.exception;

/** Indicates that all bounded attempts to allocate a free public code collided. */
public class ShortCodeAllocationException extends RuntimeException {

    /**
     * Retains the final collision for diagnostics without exposing database details.
     *
     * @param cause final database uniqueness violation
     */
    public ShortCodeAllocationException(Throwable cause) {
        super("Could not allocate a short code. Please try again.", cause);
    }
}

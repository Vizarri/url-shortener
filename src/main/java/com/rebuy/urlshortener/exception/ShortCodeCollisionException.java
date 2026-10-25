package com.rebuy.urlshortener.exception;

/** Indicates that the candidate public code is already assigned to another link. */
public class ShortCodeCollisionException extends RuntimeException {

    /**
     * Preserves the database failure while exposing a persistence-independent collision type.
     *
     * @param cause database violation of the public code's unique constraint
     */
    public ShortCodeCollisionException(Throwable cause) {
        super("Short code is already assigned.", cause);
    }
}

package com.rebuy.urlshortener.exception;

/** Indicates that no short link is assigned to the requested public code. */
public class ShortLinkNotFoundException extends RuntimeException {

    /** Creates a lookup failure without depending on HTTP response types. */
    public ShortLinkNotFoundException() {
        super("Short link not found");
    }
}

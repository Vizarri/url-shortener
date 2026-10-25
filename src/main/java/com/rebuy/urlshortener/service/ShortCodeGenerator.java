package com.rebuy.urlshortener.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Generates random public codes; uniqueness is enforced when the link is inserted. */
@Component
public class ShortCodeGenerator {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int CODE_LENGTH = 4;

    /**
     * SecureRandom is thread-safe and can be shared by concurrent request threads.
     * Each generate() call owns its output buffer; code uniqueness is enforced by
     * the database's UNIQUE constraint, with collisions retried by the service.
     */
    private final SecureRandom random = new SecureRandom();

    /**
     * Selects each Base62 character independently with equal probability.
     *
     * @return a four-character code candidate, which may already exist
     */
    public String generate() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int index = 0; index < CODE_LENGTH; index++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}

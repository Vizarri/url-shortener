package com.rebuy.urlshortener.unit.service;

import com.rebuy.urlshortener.service.ShortCodeGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the public code format without starting Spring or requiring unique random samples. */
@Tag("unit")
class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    /** Every candidate must satisfy the MVP's exact length and URL-safe alphabet. */
    @Test
    void generatesFourBase62Characters() {
        for (int sample = 0; sample < 100; sample++) {
            String code = generator.generate();

            assertThat(code).matches("[a-zA-Z0-9]{4}");
        }
    }
}

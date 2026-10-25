package com.rebuy.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the URL shortener application. */
@SpringBootApplication
public class UrlShortenerApplication {

    /**
     * Starts the HTTP server and initializes the persistence infrastructure.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}

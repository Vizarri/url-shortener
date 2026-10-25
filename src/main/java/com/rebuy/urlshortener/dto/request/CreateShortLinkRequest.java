package com.rebuy.urlshortener.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Destination submitted by the link creation form or an API client.
 *
 * @param url absolute HTTP(S) destination URL
 */
public record CreateShortLinkRequest(@NotBlank(message = "URL is required.") String url) {
}

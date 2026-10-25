package com.rebuy.urlshortener.dto.response;

/**
 * A successfully committed link, without exposing persistence identifiers.
 *
 * @param code four-character, case-sensitive Base62 code
 * @param shortUrl absolute URL served by this application
 * @param targetUrl validated original destination
 */
public record CreateShortLinkResponse(String code, String shortUrl, String targetUrl) {
}

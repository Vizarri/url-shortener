package com.rebuy.urlshortener.dto;

import com.rebuy.urlshortener.db.model.ShortLink;

/**
 * Result of creating or reusing a destination's short link.
 *
 * @param link persisted mapping for the destination
 * @param created whether this operation inserted the mapping
 */
public record ShortLinkCreationResult(ShortLink link, boolean created) {
}

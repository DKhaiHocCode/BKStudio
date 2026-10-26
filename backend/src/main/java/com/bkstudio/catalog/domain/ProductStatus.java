package com.bkstudio.catalog.domain;

/** Mirrors the chk_product_status constraint. UNLISTED = reachable by direct link only. */
public enum ProductStatus {
    DRAFT,
    ACTIVE,
    UNLISTED,
    ARCHIVED
}

package com.bkstudio.catalog.image;

/** Result of {@link ImageService#store}: {@code storageKey} is {@code products/{uuid}}; size is the 2000 rendition. */
public record StoredImage(String storageKey, int width, int height) {}

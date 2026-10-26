package com.bkstudio.catalog.image;

import java.util.Map;

/** WebP renditions keyed by target width, plus the pixel size of the largest one. */
public record ProcessedImage(Map<Integer, byte[]> renditions, int width, int height) {}

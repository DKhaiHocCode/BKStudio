package com.bkstudio.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Read-only view of {@code product_image}. */
@Entity
@Immutable
@Table(name = "product_image")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "alt_text")
    private String altText;

    private int width;
    private int height;

    @Column(name = "sort_order")
    private int sortOrder;

    protected ProductImage() {}

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getAltText() {
        return altText;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}

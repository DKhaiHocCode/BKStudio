package com.bkstudio.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Read-only view of {@code product_variant}. Stock and quota are deliberately not readable as numbers. */
@Entity
@Immutable
@Table(name = "product_variant")
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id")
    private Long productId;

    private String scale;

    @Column(name = "price_cents")
    private int priceCents;

    @Column(name = "stock_qty")
    private int stockQty;

    @Column(name = "made_to_order_enabled")
    private boolean madeToOrderEnabled;

    /** NULL = unlimited. */
    @Column(name = "made_to_order_quota")
    private Integer madeToOrderQuota;

    private boolean active;

    protected ProductVariant() {}

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getScale() {
        return scale;
    }

    public int getPriceCents() {
        return priceCents;
    }

    public boolean isInStock() {
        return active && stockQty > 0;
    }

    public boolean isMadeToOrderAvailable() {
        return active && madeToOrderEnabled && (madeToOrderQuota == null || madeToOrderQuota > 0);
    }
}

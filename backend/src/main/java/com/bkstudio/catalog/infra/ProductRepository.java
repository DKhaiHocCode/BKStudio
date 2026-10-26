package com.bkstudio.catalog.infra;

import com.bkstudio.catalog.domain.Product;
import com.bkstudio.catalog.domain.ProductStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** ACTIVE only, and only products with a sellable (active) variant, so paging totals stay exact. */
    @Query("""
            select p from Product p
            where p.status = com.bkstudio.catalog.domain.ProductStatus.ACTIVE
              and (:series is null or p.series = :series)
              and exists (select 1 from ProductVariant v where v.productId = p.id and v.active = true)
            order by p.createdAt desc, p.id desc
            """)
    Page<Product> findListed(@Param("series") String series, Pageable pageable);

    Optional<Product> findBySlugAndStatusIn(String slug, Collection<ProductStatus> statuses);
}

package com.bkstudio.catalog.infra;

import com.bkstudio.catalog.domain.ProductVariant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    List<ProductVariant> findByProductIdInAndActiveTrueOrderByIdAsc(Collection<Long> productIds);
}

package com.bkstudio.catalog.infra;

import com.bkstudio.catalog.domain.ProductImage;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByProductIdInOrderBySortOrderAscIdAsc(Collection<Long> productIds);
}

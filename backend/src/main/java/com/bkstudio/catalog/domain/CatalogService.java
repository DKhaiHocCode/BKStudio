package com.bkstudio.catalog.domain;

import com.bkstudio.catalog.api.ProductDtos;
import com.bkstudio.catalog.infra.ProductImageRepository;
import com.bkstudio.catalog.infra.ProductRepository;
import com.bkstudio.catalog.infra.ProductVariantRepository;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// shortcut: domain imports api DTOs; fine for a read-only module, move mapping to api/ (domain read models) before
// T-021/T-022 call this service.
@Service
@Transactional(readOnly = true)
public class CatalogService {

    public static final int MAX_SIZE = 50;
    public static final int MAX_PAGE = 10_000;
    public static final int MAX_SERIES_LENGTH = 200;

    // mirror of chk_product_slug_format; keeps the 404 identical
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final int[] WIDTHS = {400, 1000, 2000};

    private final ProductRepository products;
    private final ProductImageRepository images;
    private final ProductVariantRepository variants;
    private final CatalogProperties props;

    public CatalogService(
            ProductRepository products,
            ProductImageRepository images,
            ProductVariantRepository variants,
            CatalogProperties props) {
        this.products = products;
        this.images = images;
        this.variants = variants;
        this.props = props;
    }

    public ProductDtos.ProductPage list(String series, int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "page must be between 0 and " + MAX_PAGE + " and size >= 1.");
        }
        if (series != null && series.length() > MAX_SERIES_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "series is too long.");
        }
        int clamped = Math.min(size, MAX_SIZE);
        String filter = series == null || series.isBlank() ? null : series.strip();
        Page<Product> result = products.findListed(filter, PageRequest.of(page, clamped));

        List<Long> ids = result.getContent().stream().map(Product::getId).toList();
        // One batch query each for the whole page instead of two per product.
        Map<Long, List<ProductVariant>> variantsByProduct =
                variants.findByProductIdInAndActiveTrueOrderByIdAsc(ids).stream()
                        .collect(Collectors.groupingBy(ProductVariant::getProductId));
        Map<Long, List<ProductImage>> imagesByProduct = images.findByProductIdInOrderBySortOrderAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(ProductImage::getProductId));

        // A variant may be deactivated between the queries: drop such products instead of failing.
        List<ProductDtos.ListItem> items = result.getContent().stream()
                .filter(p -> variantsByProduct.containsKey(p.getId()))
                .map(p -> {
                    List<ProductVariant> vs = variantsByProduct.get(p.getId());
                    List<ProductImage> imgs = imagesByProduct.getOrDefault(p.getId(), List.of());
                    return new ProductDtos.ListItem(
                            p.getSlug(),
                            p.getName(),
                            p.getSeries(),
                            imgs.isEmpty() ? null : cover(imgs.getFirst()),
                            vs.stream()
                                    .mapToInt(ProductVariant::getPriceCents)
                                    .min()
                                    .getAsInt(),
                            vs.stream().anyMatch(ProductVariant::isInStock),
                            vs.stream().anyMatch(ProductVariant::isMadeToOrderAvailable));
                })
                .toList();
        return new ProductDtos.ProductPage(items, page, clamped, result.getTotalElements());
    }

    public ProductDtos.Detail detail(String slug) {
        // Same 404 for malformed, unknown, DRAFT and ARCHIVED slugs: nothing to learn from the response.
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw notFound();
        }
        Product p = products.findBySlugAndStatusIn(slug, Set.of(ProductStatus.ACTIVE, ProductStatus.UNLISTED))
                .orElseThrow(CatalogService::notFound);
        List<Long> ids = List.of(p.getId());

        List<ProductDtos.Image> imgs = images.findByProductIdInOrderBySortOrderAscIdAsc(ids).stream()
                .map(this::image)
                .toList();
        List<ProductDtos.Variant> vs = variants.findByProductIdInAndActiveTrueOrderByIdAsc(ids).stream()
                .map(v -> new ProductDtos.Variant(
                        v.getId(),
                        v.getScale(),
                        v.getPriceCents(),
                        new ProductDtos.InStock(v.isInStock(), v.isInStock() ? props.shipsWithinDays() : null),
                        // estimatedStartMonth stays null until the queue module (T-021) provides it.
                        new ProductDtos.MadeToOrder(v.isMadeToOrderAvailable(), null)))
                .toList();
        return new ProductDtos.Detail(
                p.getSlug(),
                p.getName(),
                p.getSeries(),
                p.getDescription(),
                p.getStatus() == ProductStatus.UNLISTED,
                imgs,
                vs);
    }

    private ProductDtos.Cover cover(ProductImage i) {
        return new ProductDtos.Cover(
                props.imageUrl(i.getStorageKey(), 1000), i.getAltText(), i.getWidth(), i.getHeight());
    }

    private ProductDtos.Image image(ProductImage i) {
        Map<String, String> srcset = new LinkedHashMap<>();
        for (int w : WIDTHS) {
            srcset.put(String.valueOf(w), props.imageUrl(i.getStorageKey(), w));
        }
        return new ProductDtos.Image(
                props.imageUrl(i.getStorageKey(), 1000), srcset, i.getAltText(), i.getWidth(), i.getHeight());
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Product not found.");
    }
}

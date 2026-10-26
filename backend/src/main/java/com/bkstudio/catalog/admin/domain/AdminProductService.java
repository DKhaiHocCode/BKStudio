package com.bkstudio.catalog.admin.domain;

import com.bkstudio.catalog.admin.api.AdminProductDtos;
import com.bkstudio.catalog.admin.api.AdminProductDtos.CreateProduct;
import com.bkstudio.catalog.admin.api.AdminProductDtos.CreateVariant;
import com.bkstudio.catalog.admin.api.AdminProductDtos.Detail;
import com.bkstudio.catalog.admin.api.AdminProductDtos.Image;
import com.bkstudio.catalog.admin.api.AdminProductDtos.ListItem;
import com.bkstudio.catalog.admin.api.AdminProductDtos.ProductPage;
import com.bkstudio.catalog.admin.api.AdminProductDtos.UpdateProduct;
import com.bkstudio.catalog.admin.api.AdminProductDtos.UpdateVariant;
import com.bkstudio.catalog.admin.api.AdminProductDtos.Variant;
import com.bkstudio.catalog.admin.domain.AdminRows.ImageRow;
import com.bkstudio.catalog.admin.domain.AdminRows.ListRow;
import com.bkstudio.catalog.admin.domain.AdminRows.ProductRow;
import com.bkstudio.catalog.admin.domain.AdminRows.VariantRow;
import com.bkstudio.catalog.admin.infra.AdminProductRepository;
import com.bkstudio.catalog.api.ProductDtos;
import com.bkstudio.catalog.domain.CatalogProperties;
import com.bkstudio.catalog.domain.CatalogService;
import com.bkstudio.catalog.domain.ProductStatus;
import com.bkstudio.catalog.image.StoredImage;
import com.bkstudio.shared.audit.AuditLogWriter;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin writes and reads of products, variants and image rows. Every write also appends an audit row. */
@Service
@Transactional
public class AdminProductService {

    public static final int MAX_IMAGES = 20;

    private static final int[] WIDTHS = {400, 1000, 2000};
    private static final int MAX_SLUG_BASE = 80;
    private static final int MAX_SLUG_ATTEMPTS = 50;
    private static final int SUFFIX_LENGTH = 10; // 36^10 ~ 2^52: unlisted links cannot be guessed
    private static final String SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminProductRepository repo;
    private final AuditLogWriter audit;
    private final CatalogProperties props;
    private final Clock clock;

    public AdminProductService(
            AdminProductRepository repo, AuditLogWriter audit, CatalogProperties props, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    // ---- products ----

    @Transactional(readOnly = true)
    public ProductPage list(ProductStatus status, String q, int page, int size) {
        if (page < 0 || page > CatalogService.MAX_PAGE || size < 1) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "page must be between 0 and " + CatalogService.MAX_PAGE + " and size >= 1.");
        }
        int clamped = Math.min(size, CatalogService.MAX_SIZE);
        String like =
                q == null || q.isBlank() ? null : "%" + escapeLike(q.strip().toLowerCase(Locale.ROOT)) + "%";
        List<ListItem> items = repo.listProducts(status, like, clamped, (long) page * clamped).stream()
                .map(this::listItem)
                .toList();
        return new ProductPage(items, page, clamped, repo.countProducts(status, like));
    }

    @Transactional(readOnly = true)
    public Detail get(long id) {
        return detail(repo.findProduct(id).orElseThrow(() -> notFound("Product")));
    }

    public Detail create(CreateProduct req) {
        ProductStatus status = req.status() == null ? ProductStatus.DRAFT : req.status();
        boolean clientSlug = req.slug() != null;
        String base = clientSlug ? req.slug() : slugify(req.name());
        String description = req.description() == null ? "" : req.description();
        String series = blankToNull(req.series());

        Optional<Long> id = Optional.empty();
        String slug = base;
        for (int attempt = 1; attempt <= MAX_SLUG_ATTEMPTS && id.isEmpty(); attempt++) {
            if (status == ProductStatus.UNLISTED) {
                slug = base + "-" + randomSuffix(); // unguessable link; a fresh suffix on every retry
            } else if (attempt > 1) {
                slug = base + "-" + attempt;
            }
            id = repo.insertProduct(slug, req.name(), series, description, status, clock.instant());
            if (id.isEmpty() && clientSlug && status != ProductStatus.UNLISTED) {
                throw new ApiException(ErrorCode.CONFLICT, "Slug '" + slug + "' is already in use.");
            }
        }
        long newId = id.orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Could not find a free slug."));
        audit.admin(
                "PRODUCT_CREATED",
                "PRODUCT",
                newId,
                null,
                fields("slug", slug, "name", req.name(), "series", series, "status", status.name()));
        return get(newId);
    }

    public Detail update(long id, UpdateProduct req) {
        ProductRow before = repo.findProduct(id).orElseThrow(() -> notFound("Product"));
        String series = blankToNull(req.series());
        String description = req.description() == null ? "" : req.description();
        if (before.version() != req.version()) {
            throw staleVersion("Product");
        }
        // Going UNLISTED makes the link the only protection, so the slug must stop being guessable.
        boolean toUnlisted = req.status() == ProductStatus.UNLISTED && before.status() != ProductStatus.UNLISTED;
        String slug = before.slug();
        boolean updated = false;
        for (int attempt = 1; attempt <= MAX_SLUG_ATTEMPTS && !updated; attempt++) {
            String newSlug = toUnlisted ? capBase(before.slug()) + "-" + randomSuffix() : null;
            int rows = repo.updateProduct(
                    id, newSlug, req.name(), series, description, req.status(), req.version(), clock.instant());
            if (rows == 1) {
                updated = true;
                slug = newSlug == null ? slug : newSlug;
            } else if (!toUnlisted
                    || repo.findProduct(id)
                            .map(ProductRow::version)
                            .filter(v -> v.equals(req.version()))
                            .isEmpty()) {
                throw staleVersion("Product"); // 0 rows with an unchanged version means only "slug taken": retry
            }
        }
        if (!updated) {
            throw new ApiException(ErrorCode.CONFLICT, "Could not find a free slug.");
        }
        audit.admin(
                "PRODUCT_UPDATED",
                "PRODUCT",
                id,
                fields(
                        "slug", before.slug(),
                        "name", before.name(),
                        "series", before.series(),
                        "status", before.status().name(),
                        "descriptionLength", before.description().length()),
                fields(
                        "slug", slug,
                        "name", req.name(),
                        "series", series,
                        "status", req.status().name(),
                        "descriptionLength", description.length()));
        return get(id);
    }

    // ---- variants ----

    public Variant createVariant(long productId, CreateVariant req) {
        if (repo.findProduct(productId).isEmpty()) {
            throw notFound("Product");
        }
        int stock = req.stockQty() == null ? 0 : req.stockQty();
        boolean mto = Boolean.TRUE.equals(req.madeToOrderEnabled());
        boolean active = req.active() == null || req.active();
        long id = repo.insertVariant(
                        productId,
                        req.scale(),
                        req.priceCents(),
                        stock,
                        mto,
                        req.madeToOrderQuota(),
                        active,
                        clock.instant())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.CONFLICT, "This product already has a variant with scale " + req.scale() + "."));
        VariantRow row = repo.findVariant(id).orElseThrow();
        audit.admin("VARIANT_CREATED", "VARIANT", id, null, variantFields(row));
        return variant(row);
    }

    public Variant updateVariant(long id, UpdateVariant req) {
        VariantRow before = repo.findVariant(id).orElseThrow(() -> notFound("Variant"));
        if (before.version() != req.version()
                || repo.updateVariant(
                                id,
                                req.priceCents(),
                                req.stockQty(),
                                req.madeToOrderEnabled(),
                                req.madeToOrderQuota(),
                                req.active(),
                                req.version(),
                                clock.instant())
                        == 0) {
            throw staleVersion("Variant");
        }
        VariantRow after = repo.findVariant(id).orElseThrow();
        audit.admin("VARIANT_UPDATED", "VARIANT", id, variantFields(before), variantFields(after));
        return variant(after);
    }

    // ---- images ----

    /** Cheap pre-check before the expensive image processing; {@link #addImage} re-checks under a row lock. */
    @Transactional(readOnly = true)
    public void assertCanAddImage(long productId) {
        if (repo.findProduct(productId).isEmpty()) {
            throw notFound("Product");
        }
        if (repo.countImages(productId) >= MAX_IMAGES) {
            throw tooManyImages();
        }
    }

    public Image addImage(long productId, StoredImage stored, String altText) {
        if (!repo.lockProduct(productId)) {
            throw notFound("Product");
        }
        if (repo.countImages(productId) >= MAX_IMAGES) {
            throw tooManyImages();
        }
        String alt = altText == null ? "" : altText;
        long id =
                repo.insertImage(productId, stored.storageKey(), alt, stored.width(), stored.height(), clock.instant());
        ImageRow row = repo.findImage(id).orElseThrow();
        audit.admin("IMAGE_ADDED", "IMAGE", id, null, imageFields(row));
        return image(row);
    }

    public List<Image> reorderImages(long productId, List<Long> imageIds) {
        if (!repo.lockProduct(productId)) {
            throw notFound("Product");
        }
        List<ImageRow> current = repo.listImages(productId);
        if (imageIds.size() != current.size()
                || !new HashSet<>(imageIds)
                        .equals(new HashSet<>(current.stream().map(ImageRow::id).toList()))) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "imageIds must list every image of the product exactly once.");
        }
        repo.setSortOrders(productId, imageIds);
        audit.admin(
                "IMAGE_REORDERED",
                "PRODUCT",
                productId,
                fields("imageIds", current.stream().map(ImageRow::id).toList()),
                fields("imageIds", imageIds));
        return repo.listImages(productId).stream().map(this::image).toList();
    }

    public Image updateAlt(long imageId, String altText) {
        ImageRow before = repo.findImage(imageId).orElseThrow(() -> notFound("Image"));
        if (repo.updateAlt(imageId, altText) == 0) {
            throw notFound("Image"); // deleted in parallel
        }
        audit.admin(
                "IMAGE_ALT_UPDATED", "IMAGE", imageId, fields("altText", before.altText()), fields("altText", altText));
        return image(new ImageRow(
                before.id(),
                before.productId(),
                before.storageKey(),
                altText,
                before.width(),
                before.height(),
                before.sortOrder()));
    }

    /** Deletes the row and audits it; returns the storage key so the caller can delete the files after commit. */
    public String deleteImage(long imageId) {
        ImageRow row = repo.findImage(imageId).orElseThrow(() -> notFound("Image"));
        if (repo.deleteImage(imageId) == 0) {
            throw notFound("Image"); // a parallel delete won: its caller owns the key and the audit row
        }
        audit.admin("IMAGE_DELETED", "IMAGE", imageId, imageFields(row), null);
        return row.storageKey();
    }

    // ---- mapping ----

    private Detail detail(ProductRow p) {
        return new Detail(
                p.id(),
                p.slug(),
                p.name(),
                p.series(),
                p.description(),
                p.status(),
                p.version(),
                p.createdAt(),
                p.updatedAt(),
                repo.listVariants(p.id()).stream()
                        .map(AdminProductService::variant)
                        .toList(),
                repo.listImages(p.id()).stream().map(this::image).toList());
    }

    private ListItem listItem(ListRow r) {
        ProductDtos.Cover cover = r.coverKey() == null
                ? null
                : new ProductDtos.Cover(
                        props.imageUrl(r.coverKey(), 1000), r.coverAlt(), r.coverWidth(), r.coverHeight());
        return new ListItem(
                r.id(),
                r.slug(),
                r.name(),
                r.series(),
                r.status(),
                r.version(),
                r.updatedAt(),
                r.variantCount(),
                r.imageCount(),
                cover);
    }

    private static Variant variant(VariantRow v) {
        return new Variant(
                v.id(),
                v.scale(),
                v.priceCents(),
                v.stockQty(),
                v.madeToOrderEnabled(),
                v.madeToOrderQuota(),
                v.active(),
                v.version());
    }

    private Image image(ImageRow i) {
        Map<String, String> urls = new LinkedHashMap<>();
        for (int w : WIDTHS) {
            urls.put(String.valueOf(w), props.imageUrl(i.storageKey(), w));
        }
        return new AdminProductDtos.Image(i.id(), i.sortOrder(), i.altText(), urls, i.width(), i.height());
    }

    private static Map<String, Object> variantFields(VariantRow v) {
        return fields(
                "productId", v.productId(),
                "scale", v.scale(),
                "priceCents", v.priceCents(),
                "stockQty", v.stockQty(),
                "madeToOrderEnabled", v.madeToOrderEnabled(),
                "madeToOrderQuota", v.madeToOrderQuota(),
                "active", v.active());
    }

    private static Map<String, Object> imageFields(ImageRow i) {
        return fields(
                "productId", i.productId(),
                "storageKey", i.storageKey(),
                "altText", i.altText(),
                "sortOrder", i.sortOrder());
    }

    // ---- helpers ----

    /** Map.of rejects null values; audit snapshots legitimately contain them. */
    private static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found.");
    }

    private static ApiException staleVersion(String what) {
        return new ApiException(
                ErrorCode.VERSION_CONFLICT, what + " was changed by someone else. Reload and try again.");
    }

    private static ApiException tooManyImages() {
        return new ApiException(ErrorCode.CONFLICT, "A product can have at most " + MAX_IMAGES + " images.");
    }

    /** Lower-case ASCII kebab-case; 'product' when nothing usable is left. */
    static String slugify(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'd')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > MAX_SLUG_BASE) {
            s = s.substring(0, MAX_SLUG_BASE).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "product" : s;
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Keeps base + "-" + suffix well inside the slug format; a base that is already long is cut and re-trimmed. */
    private static String capBase(String s) {
        return s.length() <= MAX_SLUG_BASE ? s : s.substring(0, MAX_SLUG_BASE).replaceAll("-+$", "");
    }

    private static String randomSuffix() {
        StringBuilder sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(SUFFIX_CHARS.charAt(RANDOM.nextInt(SUFFIX_CHARS.length())));
        }
        return sb.toString();
    }
}

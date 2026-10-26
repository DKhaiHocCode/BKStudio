package com.bkstudio.catalog.admin.api;

import com.bkstudio.catalog.admin.api.AdminProductDtos.CreateProduct;
import com.bkstudio.catalog.admin.api.AdminProductDtos.CreateVariant;
import com.bkstudio.catalog.admin.api.AdminProductDtos.Detail;
import com.bkstudio.catalog.admin.api.AdminProductDtos.Image;
import com.bkstudio.catalog.admin.api.AdminProductDtos.ProductPage;
import com.bkstudio.catalog.admin.api.AdminProductDtos.ReorderImages;
import com.bkstudio.catalog.admin.api.AdminProductDtos.UpdateAlt;
import com.bkstudio.catalog.admin.api.AdminProductDtos.UpdateProduct;
import com.bkstudio.catalog.admin.api.AdminProductDtos.UpdateVariant;
import com.bkstudio.catalog.admin.api.AdminProductDtos.Variant;
import com.bkstudio.catalog.admin.domain.AdminImageService;
import com.bkstudio.catalog.admin.domain.AdminProductService;
import com.bkstudio.catalog.domain.ProductStatus;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Admin product management. Everything under /api/admin/** already requires ROLE_ADMIN + CSRF (SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminProductController {

    private final AdminProductService products;
    private final AdminImageService images;

    public AdminProductController(AdminProductService products, AdminImageService images) {
        this.products = products;
        this.images = images;
    }

    @GetMapping("/products")
    public ProductPage list(
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return products.list(status, q, page, size);
    }

    @GetMapping("/products/{id}")
    public Detail get(@PathVariable long id) {
        return products.get(id);
    }

    @PostMapping("/products")
    public ResponseEntity<Detail> create(@Valid @RequestBody CreateProduct req) {
        Detail created = products.create(req);
        return ResponseEntity.created(URI.create("/api/admin/products/" + created.id()))
                .body(created);
    }

    @PutMapping("/products/{id}")
    public Detail update(@PathVariable long id, @Valid @RequestBody UpdateProduct req) {
        return products.update(id, req);
    }

    @PostMapping("/products/{id}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    public Variant createVariant(@PathVariable long id, @Valid @RequestBody CreateVariant req) {
        return products.createVariant(id, req);
    }

    @PutMapping("/variants/{id}")
    public Variant updateVariant(@PathVariable long id, @Valid @RequestBody UpdateVariant req) {
        return products.updateVariant(id, req);
    }

    @PostMapping(path = "/products/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Image upload(
            @PathVariable long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) @Size(max = 200) String altText)
            throws IOException {
        if (file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "file must not be empty.");
        }
        // The client content-type is ignored: ImageService sniffs the real format from the bytes.
        return images.upload(id, file.getInputStream(), altText);
    }

    @PutMapping("/products/{id}/images/order")
    public List<Image> reorder(@PathVariable long id, @Valid @RequestBody ReorderImages req) {
        return products.reorderImages(id, req.imageIds());
    }

    @PatchMapping("/images/{id}")
    public Image updateAlt(@PathVariable long id, @Valid @RequestBody UpdateAlt req) {
        return products.updateAlt(id, req.altText());
    }

    @DeleteMapping("/images/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(@PathVariable long id) {
        images.delete(id);
    }
}

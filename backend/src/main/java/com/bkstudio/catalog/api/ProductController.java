package com.bkstudio.catalog.api;

import com.bkstudio.catalog.domain.CatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final CatalogService catalog;

    public ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ProductDtos.ProductPage list(
            @RequestParam(required = false) String series,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return catalog.list(series, page, size);
    }

    @GetMapping("/{slug}")
    public ProductDtos.Detail detail(@PathVariable String slug) {
        return catalog.detail(slug);
    }
}

package com.example.eshop.api;

import com.example.eshop.ai.ProductSemanticSearchService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@RestController
class ProductSemanticSearchController {
    private final ObjectProvider<ProductSemanticSearchService> semantic;

    ProductSemanticSearchController(ObjectProvider<ProductSemanticSearchService> semantic) {
        this.semantic = semantic;
    }

    @GetMapping("/api/products/semantic-search")
    public ResponseEntity<List<ProductMatch>> search(@RequestParam(required = false) String q,
                                                      @RequestParam(required = false) Long categoryId) {
        var service = semantic.getIfAvailable();
        if (service == null) {
            throw ProductSemanticSearchService.unavailable();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.search(q, categoryId).stream().map(ProductMatch::from).toList());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> searchFailure(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(failure.getBody());
    }

    record ProductMatch(Long id, String name, String description, BigDecimal price, String imageUrl,
                        Long categoryId, String categoryName, boolean inStock, Float score) {
        static ProductMatch from(ProductSemanticSearchService.Hit hit) {
            var p = hit.product();
            return new ProductMatch(p.getId(), p.getName(), p.getDescription(), p.getPrice(), p.getImageUrl(),
                    p.getCategory().getId(), p.getCategory().getName(), p.getQuantity() > 0, hit.score());
        }
    }
}

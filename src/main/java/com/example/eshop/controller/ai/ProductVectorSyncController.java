package com.example.eshop.controller.ai;

import com.example.eshop.service.ai.ProductVectorSyncService;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@Profile("ai-dev")
@RequestMapping("/api/dev/ai/products")
@PreAuthorize("hasRole('SELLER')")
public class ProductVectorSyncController {
    private final ProductVectorSyncService sync;

    ProductVectorSyncController(ProductVectorSyncService sync) {
        this.sync = sync;
    }

    @PostMapping("/sync")
    public ProductVectorSyncService.SyncReport syncAll() {
        return sync.syncAll();
    }

    @PostMapping("/{id}/sync")
    public Map<String, Object> syncProduct(@PathVariable long id) {
        return Map.of("productId", id, "outcome", sync.syncProduct(id));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> syncFailure(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(failure.getBody());
    }
}

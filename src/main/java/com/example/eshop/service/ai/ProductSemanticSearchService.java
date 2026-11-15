package com.example.eshop.service.ai;

import com.example.eshop.infrastructure.ai.QdrantProductIndex;

import com.example.eshop.entity.Product;
import com.example.eshop.repository.ProductRepository;
import io.qdrant.client.grpc.Points.ScoredPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.qdrant.client.ValueFactory.value;

@Service
@Profile("ai-dev")
@ConditionalOnProperty(name = "eshop.ai.semantic-search.enabled", havingValue = "true", matchIfMissing = true)
public class ProductSemanticSearchService {
    private static final Pattern SPACES = Pattern.compile("(?U)\\s+");
    private static final long MINUTE = Duration.ofMinutes(1).toNanos();
    private final ProductEmbeddingService embeddings;
    private final QdrantProductIndex index;
    private final ProductRepository products;
    private final int topK;
    private final float threshold;
    private final int maxLength;
    private final Duration deadline;
    private final Semaphore concurrent;
    private final int requestsPerMinute;
    private long windowStart = System.nanoTime();
    private int requests;

    ProductSemanticSearchService(ProductEmbeddingService embeddings, QdrantProductIndex index,
                                 ProductRepository products,
                                 @Value("${eshop.ai.semantic-search.top-k:5}") int topK,
                                 @Value("${eshop.ai.semantic-search.threshold:0.55}") float threshold,
                                 @Value("${eshop.ai.semantic-search.max-query-length:300}") int maxLength,
                                 @Value("${eshop.ai.semantic-search.qdrant-timeout-ms:3000}") long timeoutMs,
                                 @Value("${eshop.ai.semantic-search.max-concurrent:4}") int maxConcurrent,
                                 @Value("${eshop.ai.semantic-search.requests-per-minute:60}") int requestsPerMinute) {
        this.embeddings = embeddings;
        this.index = index;
        this.products = products;
        this.topK = Math.max(1, Math.min(topK, 30));
        if (!Float.isFinite(threshold) || threshold < -1 || threshold > 1) {
            throw new IllegalArgumentException("eshop.ai.semantic-search.threshold must be between -1 and 1");
        }
        this.threshold = threshold;
        this.maxLength = Math.max(1, Math.min(maxLength, ProductEmbeddingService.MAX_TEXT_LENGTH));
        this.deadline = Duration.ofMillis(Math.max(250, Math.min(timeoutMs, 10_000)));
        this.concurrent = new Semaphore(Math.max(1, Math.min(maxConcurrent, 16)));
        this.requestsPerMinute = Math.max(1, requestsPerMinute);
    }

    public int maxLength() {
        return maxLength;
    }

    public List<Hit> search(String rawQuery, Long categoryId) {
        return search(rawQuery, categoryId, 0).hits();
    }

    public SearchResult search(String rawQuery, Long categoryId, int page) {
        return search(rawQuery, categoryId, page, List.of());
    }

    SearchResult search(String rawQuery, Long categoryId, int page, Collection<Long> excludedIds) {
        String query = normalize(rawQuery);
        if (categoryId != null && categoryId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Danh mục tìm kiếm không hợp lệ.");
        }
        var filters = new Filters(categoryId);
        if (!concurrent.tryAcquire()) {
            throw busy();
        }
        try {
            if (!reserveRequest()) {
                throw busy();
            }
            List<ScoredPoint> neighbors;
            try {
                //Dung AI
                float[] vector = embeddings.embed(query);
                int limit = topK * 3 + excludedIds.size();
                //Tim Vector trong Qdrant
                neighbors = index.search(vector, limit, threshold, categoryId, deadline);
            } catch (RuntimeException failure) {
                // Do not expose provider diagnostics or silently switch to SQL.
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Tìm kiếm AI tạm thời không hoạt động. Vui lòng thử lại sau.");
            }
            var ranks = new LinkedHashMap<Long, Float>();
            for (ScoredPoint neighbor : neighbors) {
                long id = neighbor.getPayloadOrDefault("product_id", value(0L)).getIntegerValue();
                if (id > 0 && !excludedIds.contains(id) && Float.isFinite(neighbor.getScore()) && neighbor.getScore() >= threshold) {
                    ranks.putIfAbsent(id, neighbor.getScore());
                }
            }
            if (ranks.isEmpty()) {
                return new SearchResult(List.of(), SearchMode.VECTOR, filters, 0, 0, 1);
            }
            var found = products.loadRankedProducts(ranks.keySet()).stream()
                    .collect(Collectors.toMap(Product::getId, Function.identity()));
            var matching = ranks.entrySet().stream().filter(rank -> found.containsKey(rank.getKey()))
                    .map(rank -> new Hit(found.get(rank.getKey()), rank.getValue()))
                    .filter(hit -> filters.matches(hit.product()));
            var hits = matching.limit(topK).toList();
            return new SearchResult(hits, SearchMode.VECTOR, filters, hits.size(), 0, 1);
        } finally {
            concurrent.release();
        }
    }

    private String normalize(String rawQuery) {
        if (rawQuery == null || rawQuery.length() > maxLength) {
            throw invalidQuery();
        }
        String normalized = SPACES.matcher(Normalizer.normalize(rawQuery, Normalizer.Form.NFC)).replaceAll(" ").strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw invalidQuery();
        }
        return normalized;
    }

    private synchronized boolean reserveRequest() {
        long now = System.nanoTime();
        if (now - windowStart >= MINUTE) {
            windowStart = now;
            requests = 0;
        }
        if (requests >= requestsPerMinute) {
            return false;
        }
        requests++;
        return true;
    }

    private ResponseStatusException invalidQuery() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nhập câu tìm kiếm từ 1 đến " + maxLength + " ký tự.");
    }

    private static ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Tìm kiếm AI đang bận. Vui lòng thử lại sau.");
    }

    public static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Tìm kiếm AI chưa được bật.");
    }

    public enum SearchMode { VECTOR }

    public record Filters(Long categoryId) {
        boolean matches(Product product) {
            return categoryId == null || product.getCategory() != null
                    && Objects.equals(categoryId, product.getCategory().getId());
        }
    }

    public record SearchResult(List<Hit> hits, SearchMode mode, Filters filters, long total, int page, int totalPages) {}
    public record Hit(Product product, Float score) {}
}

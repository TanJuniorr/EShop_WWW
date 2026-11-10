package com.example.eshop.ai;

import com.example.eshop.entity.Product;
import com.example.eshop.repository.CategoryRepository;
import com.example.eshop.repository.ProductRepository;
import io.qdrant.client.grpc.Points.ScoredPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Collection;
import java.math.BigDecimal;
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
    private static final Pattern CATEGORY_PREFIX = Pattern.compile(
            "^(?:cho (?:toi|tui|minh|em)(?: xem)?|(?:(?:toi|tui|minh|em) )?"
                    + "(?:dang tim|muon mua|can mua|can tim|tim mua|tim kiem|tim|liet ke|mua|can|muon|goi y(?: cho (?:toi|tui|minh|em))?)) ");
    private static final Pattern CATEGORY_COMPARISON = Pattern.compile("^(?:va|hoac|hay|so voi|vs) ");
    private static final int CATALOG_PAGE_SIZE = 12;
    private static final long MINUTE = Duration.ofMinutes(1).toNanos();
    private final ProductEmbeddingService embeddings;
    private final QdrantProductIndex index;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ProductSearchQueryParser parser = new ProductSearchQueryParser();
    private final int topK;
    private final float threshold;
    private final int maxLength;
    private final Duration deadline;
    private final Semaphore concurrent;
    private final int requestsPerMinute;
    private long windowStart = System.nanoTime();
    private int requests;

    ProductSemanticSearchService(ProductEmbeddingService embeddings, QdrantProductIndex index,
                                 ProductRepository products, CategoryRepository categories,
                                 @Value("${eshop.ai.semantic-search.top-k:5}") int topK,
                                 @Value("${eshop.ai.semantic-search.threshold:0.55}") float threshold,
                                 @Value("${eshop.ai.semantic-search.max-query-length:300}") int maxLength,
                                 @Value("${eshop.ai.semantic-search.qdrant-timeout-ms:3000}") long timeoutMs,
                                 @Value("${eshop.ai.semantic-search.max-concurrent:4}") int maxConcurrent,
                                 @Value("${eshop.ai.semantic-search.requests-per-minute:60}") int requestsPerMinute) {
        this.embeddings = embeddings;
        this.index = index;
        this.products = products;
        this.categories = categories;
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
        return search(rawQuery, categoryId, page, excludedIds, null);
    }

    SearchResult search(String rawQuery, Long categoryId, int page, Collection<Long> excludedIds, String brand) {
        String query = normalize(rawQuery);
        if (categoryId != null && categoryId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Danh mục tìm kiếm không hợp lệ.");
        }
        var parsed = parser.parse(query);
        var parsedRequirements = CatalogRequirements.parse(categorySubject(parsed.semantic()));
        var requirements = brand == null ? parsedRequirements : parsedRequirements.withBrand(brand);
        String subject = requirements.remaining();
        String categorySubject = requirements.phoneModel() == null ? subject : "dien thoai";
        var inferred = (categoryId == null || requirements.present()) && !categorySubject.isEmpty()
                ? inferCategory(categorySubject) : null;
        Long effectiveCategoryId = categoryId != null || inferred == null ? categoryId : inferred.id();
        var filters = new Filters(effectiveCategoryId,
                parsed.min() == null ? null : parsed.min().amount(), parsed.min() != null && parsed.min().inclusive(),
                parsed.max() == null ? null : parsed.max().amount(), parsed.max() != null && parsed.max().inclusive(),
                parsed.inStock(), orderLabel(parsed.order()), requirements);

        boolean categoryOnly = inferred != null && subject.equals(inferred.words());
        if (!requirements.present() && (parsed.semantic().isEmpty() || categoryOnly)) {
            var result = products.searchCatalog(filters.categoryId(), filters.min(), filters.minInclusive(),
                    filters.max(), filters.maxInclusive(), filters.inStock(),
                    PageRequest.of(Math.max(0, page), CATALOG_PAGE_SIZE, catalogSort(parsed.order())));
            return new SearchResult(result.getContent().stream().map(product -> new Hit(product, null)).toList(),
                    SearchMode.FILTERS, filters, result.getTotalElements(), result.getNumber(), result.getTotalPages());
        }

        // Filter before ranking, so eligible products outside the unrestricted top K are still considered.
        List<Long> candidates;
        if (requirements.present()) {
            if (inferred != null && !inferred.id().equals(effectiveCategoryId)) {
                return requirementPage(List.of(), filters, page);
            }
            var matches = products.searchCatalog(filters.categoryId(), filters.min(), filters.minInclusive(),
                            filters.max(), filters.maxInclusive(), filters.inStock(), Pageable.unpaged(catalogSort(parsed.order())))
                    .stream().filter(filters::matches).filter(product -> !excludedIds.contains(product.getId())).toList();
            if (matches.isEmpty() || subject.isEmpty() || categoryOnly) {
                return requirementPage(matches, filters, page);
            }
            candidates = matches.stream().map(Product::getId).toList();
        } else {
            candidates = parsed.hasConstraints()
                    ? products.hybridCandidateIds(filters.categoryId(), filters.min(), filters.minInclusive(),
                            filters.max(), filters.maxInclusive(), filters.inStock())
                    : null;
        }
        SearchMode mode = candidates != null || effectiveCategoryId != null ? SearchMode.HYBRID : SearchMode.VECTOR;
        if (candidates != null && candidates.isEmpty()) {
            return new SearchResult(List.of(), SearchMode.FILTERS, filters, 0, 0, 1);
        }
        if (!concurrent.tryAcquire()) {
            throw busy();
        }
        try {
            if (!reserveRequest()) {
                throw busy();
            }
            List<ScoredPoint> neighbors;
            try {
                float[] vector = embeddings.embed(parsed.semantic());
                int limit = parsed.order() == ProductSearchQueryParser.Order.RELEVANCE
                        ? topK * 3 + excludedIds.size() : candidates.size();
                neighbors = candidates == null
                        ? index.search(vector, limit, threshold, effectiveCategoryId, deadline)
                        : index.search(vector, limit, threshold, effectiveCategoryId, candidates, deadline);
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
                return new SearchResult(List.of(), mode, filters, 0, 0, 1);
            }
            var found = products.loadRankedProducts(ranks.keySet()).stream()
                    .collect(Collectors.toMap(Product::getId, Function.identity()));
            var matching = ranks.entrySet().stream().filter(rank -> found.containsKey(rank.getKey()))
                    .map(rank -> new Hit(found.get(rank.getKey()), rank.getValue()))
                    .filter(hit -> filters.matches(hit.product()));
            if (parsed.order() != ProductSearchQueryParser.Order.RELEVANCE) {
                matching = matching.sorted(hitOrder(parsed.order()));
            }
            var hits = matching.limit(topK).toList();
            return new SearchResult(hits, mode, filters, hits.size(), 0, 1);
        } finally {
            concurrent.release();
        }
    }

    private static SearchResult requirementPage(List<Product> matches, Filters filters, int page) {
        int pageNumber = Math.max(0, page);
        int start = (int) Math.min((long) pageNumber * CATALOG_PAGE_SIZE, matches.size());
        int end = start + Math.min(CATALOG_PAGE_SIZE, matches.size() - start);
        var hits = matches.subList(start, end).stream().map(product -> new Hit(product, null)).toList();
        int pages = (int) Math.ceil((double) matches.size() / CATALOG_PAGE_SIZE);
        return new SearchResult(hits, SearchMode.FILTERS, filters, matches.size(), pageNumber, pages);
    }

    private CategoryName inferCategory(String subject) {
        // Only narrow when the requested product type leads the query. "Chuột cho laptop"
        // targets mice, whereas "phụ kiện cho điện thoại" must not be narrowed to phones.
        var catalog = categories.findAllByOrderByNameAsc().stream()
                .map(category -> new CategoryName(category.getId(), ProductSearchQueryParser.words(category.getName())))
                .filter(category -> !category.words().isEmpty())
                .toList();
        var matches = catalog.stream().filter(category -> startsWithPhrase(subject, category.words()))
                .sorted((a, b) -> Integer.compare(b.words().length(), a.words().length())).toList();
        if (matches.isEmpty() || (matches.size() > 1 && matches.get(0).words().equals(matches.get(1).words()))) {
            return null;
        }
        var match = matches.get(0);
        String tail = subject.substring(match.words().length()).stripLeading();
        var comparison = CATEGORY_COMPARISON.matcher(tail);
        if (comparison.find()) {
            String alternative = tail.substring(comparison.end());
            if (catalog.stream().anyMatch(category -> !category.id().equals(match.id())
                    && startsWithPhrase(alternative, category.words()))) {
                return null;
            }
        }
        return match;
    }

    private static boolean startsWithPhrase(String text, String phrase) {
        return text.equals(phrase) || text.startsWith(phrase + " ");
    }

    private static String categorySubject(String text) {
        return CATEGORY_PREFIX.matcher(ProductSearchQueryParser.words(text)).replaceFirst("")
                .replaceFirst("^(?:tat ca(?: cac)?|cac|nhung) ", "")
                .replaceFirst("^san pham ", "");
    }

    private record CategoryName(Long id, String words) {}

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

    private static String orderLabel(ProductSearchQueryParser.Order order) {
        return switch (order) {
            case PRICE_ASC -> "Giá tăng dần";
            case PRICE_DESC -> "Giá giảm dần";
            case NEWEST -> "Mới nhất";
            default -> null;
        };
    }

    private static Sort catalogSort(ProductSearchQueryParser.Order order) {
        Sort stable = Sort.by(Sort.Direction.DESC, "id");
        return switch (order) {
            case PRICE_ASC -> Sort.by("price").and(stable);
            case PRICE_DESC -> Sort.by(Sort.Direction.DESC, "price").and(stable);
            case NEWEST -> Sort.by(Sort.Direction.DESC, "createdAt").and(stable);
            default -> stable;
        };
    }

    private static Comparator<Hit> hitOrder(ProductSearchQueryParser.Order order) {
        return switch (order) {
            case PRICE_ASC -> Comparator.comparing(hit -> hit.product().getPrice());
            case PRICE_DESC -> Comparator.comparing((Hit hit) -> hit.product().getPrice()).reversed();
            case NEWEST -> Comparator.comparing((Hit hit) -> hit.product().getCreatedAt(),
                    Comparator.nullsLast(Comparator.reverseOrder()));
            default -> throw new IllegalArgumentException("Explicit order required");
        };
    }

    public enum SearchMode { FILTERS, VECTOR, HYBRID }

    public record Filters(Long categoryId, BigDecimal min, boolean minInclusive,
                          BigDecimal max, boolean maxInclusive, boolean inStock, String orderLabel,
                          CatalogRequirements requirements) {
        boolean matches(Product product) {
            if ((categoryId != null && !Objects.equals(categoryId, product.getCategory().getId()))
                    || (inStock && product.getQuantity() <= 0)) {
                return false;
            }
            int lower = min == null ? 1 : product.getPrice().compareTo(min);
            int upper = max == null ? -1 : product.getPrice().compareTo(max);
            return (lower > 0 || lower == 0 && minInclusive)
                    && (upper < 0 || upper == 0 && maxInclusive) && requirements.matches(product);
        }
    }

    public record SearchResult(List<Hit> hits, SearchMode mode, Filters filters, long total, int page, int totalPages) {}
    public record Hit(Product product, Float score) {}
}

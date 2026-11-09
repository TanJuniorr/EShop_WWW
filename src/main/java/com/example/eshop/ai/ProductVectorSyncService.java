package com.example.eshop.ai;

import com.example.eshop.repository.ProductIndexSnapshot;
import com.example.eshop.repository.ProductRepository;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static com.example.eshop.ai.QdrantProductIndex.SOURCE;

@Service
@Profile("ai-dev")
public class ProductVectorSyncService {
    private static final Logger LOG = LoggerFactory.getLogger(ProductVectorSyncService.class);
    private static final String HASH = "content_hash";
    private final ProductRepository products;
    private final ProductEmbeddingService embeddings;
    private final QdrantProductIndex index;
    private final PageRequest page;
    private final int attempts;
    private final Object[] locks = IntStream.range(0, 32).mapToObj(i -> new Object()).toArray();
    private final AtomicBoolean backfillRunning = new AtomicBoolean();

    ProductVectorSyncService(ProductRepository products, ProductEmbeddingService embeddings, QdrantProductIndex index,
                             @Value("${eshop.ai.product-sync.batch-size:20}") int batchSize,
                             @Value("${eshop.ai.product-sync.max-attempts:3}") int attempts) {
        this.products = products;
        this.embeddings = embeddings;
        this.index = index;
        this.page = PageRequest.of(0, Math.max(1, Math.min(batchSize, 100)));
        this.attempts = Math.max(1, Math.min(attempts, 3));
    }

    public Outcome syncProduct(long productId) {
        if (productId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID sản phẩm phải lớn hơn 0.");
        }
        for (int attempt = 1; ; attempt++) {
            try {
                synchronized (locks[Math.floorMod(Long.hashCode(productId), locks.length)]) {
                    return reconcile(productId);
                }
            } catch (ResponseStatusException failure) {
                if (attempt >= attempts || !retryable(failure)) {
                    throw failure;
                }
                LOG.warn("Product index retry: productId={}, attempt={}, reason={}",
                        productId, attempt, failure.getReason());
                pause(attempt);
            }
        }
    }

    public SyncReport syncAll() {
        if (!backfillRunning.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đang có một lần đồng bộ toàn bộ.");
        }
        try {
            index.ensureCollection(true);
            return syncBatch(null);
        } finally {
            backfillRunning.set(false);
        }
    }

    SyncReport syncBatch(Long categoryId) {
        long cursor = 0;
        long lastId = products.maxIndexId();
        int updated = 0, deleted = 0, skipped = 0, failed = 0;
        List<FailedProduct> failures = new ArrayList<>();
        while (cursor < lastId && !Thread.currentThread().isInterrupted()) {
            List<Long> ids = products.indexIds(cursor, categoryId, page);
            if (ids.isEmpty()) {
                break;
            }
            for (long productId : ids) {
                if (productId > lastId || Thread.currentThread().isInterrupted()) {
                    break;
                }
                try {
                    switch (syncProduct(productId)) {
                        case UPSERTED -> updated++;
                        case DELETED -> deleted++;
                        case SKIPPED -> skipped++;
                    }
                } catch (RuntimeException failure) {
                    failed++;
                    String reason = safeReason(failure);
                    LOG.warn("Product index failed: productId={}, reason={}", productId, reason);
                    if (failures.size() < 100) {
                        failures.add(new FailedProduct(productId, reason));
                    }
                }
            }
            cursor = ids.get(ids.size() - 1);
        }
        return new SyncReport(updated + deleted, updated, deleted, skipped, failed, List.copyOf(failures));
    }

    private Outcome reconcile(long productId) {
        var snapshot = products.indexSnapshot(productId);
        if (!index.ensureCollection(snapshot.isPresent())) {
            return Outcome.SKIPPED;
        }
        var pointId = id(UUID.nameUUIDFromBytes(("eshop:product:" + productId).getBytes(StandardCharsets.UTF_8)));
        var existing = index.find(pointId);
        if (existing.isPresent() && !owns(existing.get(), productId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Point đã tồn tại với metadata khác.");
        }
        if (snapshot.isEmpty()) {
            if (existing.isEmpty()) {
                return Outcome.SKIPPED;
            }
            index.delete(pointId);
            return Outcome.DELETED;
        }

        ProductIndexSnapshot product = snapshot.get();
        String name = clean(product.name());
        String category = clean(product.categoryName());
        String description = clean(product.description());
        String text = "Tên sản phẩm: " + name + "\nDanh mục: " + category + "\nMô tả: " + description;
        String hash = contentHash(product.categoryId(), text);
        if (existing.map(p -> p.getPayloadOrDefault(HASH, value("")).getStringValue().equals(hash)).orElse(false)) {
            return Outcome.SKIPPED;
        }

        float[] embedding = embeddings.embed(text);
        var point = PointStruct.newBuilder().setId(pointId).setVectors(vectors(embedding))
                .putAllPayload(Map.of(
                        "source", value(SOURCE), "product_id", value(productId),
                        "product_name", value(name), "category_name", value(category),
                        "doc_content", value(text), HASH, value(hash),
                        "embedding_model", value(AiDevConfiguration.MODEL)));
        if (product.categoryId() != null) {
            point.putPayload("category_id", value(product.categoryId()));
        }
        index.upsert(point.build());
        return Outcome.UPSERTED;
    }

    private static boolean owns(RetrievedPoint point, long productId) {
        return SOURCE.equals(point.getPayloadOrDefault("source", value("")).getStringValue())
                && point.getPayloadOrDefault("product_id", value(0L)).getIntegerValue() == productId;
    }

    private static String clean(String text) {
        return text == null ? "" : text.strip();
    }

    private static String contentHash(Long categoryId, String text) {
        try {
            String content = AiDevConfiguration.MODEL + "\n" + AiDevConfiguration.DIMENSIONS + "\n" + categoryId + "\n" + text;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean retryable(ResponseStatusException e) {
        return (e instanceof ProductEmbeddingService.ProviderFailure provider && provider.retryable())
                || (e instanceof QdrantProductIndex.Failure qdrant && qdrant.retryable());
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(250L << (attempt - 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Đồng bộ bị ngắt.");
        }
    }

    static String safeReason(RuntimeException failure) {
        return failure instanceof ResponseStatusException status ? status.getReason() : "Lỗi đọc hoặc đồng bộ sản phẩm.";
    }

    public enum Outcome { UPSERTED, DELETED, SKIPPED }
    public record FailedProduct(long productId, String reason) {}
    public record SyncReport(int succeeded, int upserted, int deleted, int skipped, int failed,
                             List<FailedProduct> failures) {}
}

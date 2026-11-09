package com.example.eshop.ai;

import com.example.eshop.service.CatalogIndexChange;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
@Profile("ai-dev")
@ConditionalOnProperty(name = "eshop.ai.product-sync.auto-enabled", havingValue = "true", matchIfMissing = true)
class ProductVectorSyncListener {
    private static final Logger LOG = LoggerFactory.getLogger(ProductVectorSyncListener.class);
    private final ProductVectorSyncService sync;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), task -> new Thread(task, "product-vector-sync"));

    ProductVectorSyncListener(ProductVectorSyncService sync) {
        this.sync = sync;
    }

    @TransactionalEventListener
    public void productChanged(CatalogIndexChange.ProductChanged event) {
        submit("productId", event.id(), () -> sync.syncProduct(event.id()));
    }

    @TransactionalEventListener
    public void categoryRenamed(CatalogIndexChange.CategoryRenamed event) {
        submit("categoryId", event.id(), () -> sync.syncBatch(event.id()));
    }

    private void submit(String kind, long id, Runnable task) {
        try {
            worker.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException failure) {
                    LOG.warn("Product index failed after commit: {}={}, reason={}",
                            kind, id, ProductVectorSyncService.safeReason(failure));
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.warn("Product index queue unavailable after commit: {}={}; manual sync required", kind, id);
        }
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
    }
}

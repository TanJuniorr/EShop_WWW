package com.example.eshop.service.ai;

import com.example.eshop.infrastructure.ai.QdrantProductIndex;

import com.example.eshop.repository.ProductIndexSnapshot;
import com.example.eshop.repository.ProductRepository;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionConfig;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionParams;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.eshop.service.ai.ProductVectorSyncService.Outcome.*;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static io.qdrant.client.ValueFactory.value;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductVectorSyncServiceTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductEmbeddingService embeddings = mock(ProductEmbeddingService.class);
    private final QdrantProductIndex index = mock(QdrantProductIndex.class);
    private final Map<PointId, RetrievedPoint> points = new HashMap<>();
    private final ProductVectorSyncService sync = new ProductVectorSyncService(products, embeddings, index, 1, 2);

    @BeforeEach
    void setup() {
        when(index.ensureCollection(anyBoolean())).thenReturn(true);
        when(index.find(any())).thenAnswer(i -> Optional.ofNullable(points.get(i.getArgument(0))));
        doAnswer(i -> {
            PointStruct p = i.getArgument(0);
            points.put(p.getId(), RetrievedPoint.newBuilder().setId(p.getId()).putAllPayload(p.getPayloadMap()).build());
            return null;
        }).when(index).upsert(any());
        doAnswer(i -> { points.remove(i.getArgument(0)); return null; }).when(index).delete(any());
        float[] vector = new float[768];
        vector[0] = 1;
        when(embeddings.embed(anyString())).thenReturn(vector);
    }

    @Test
    void insertsUpdatesOnlyIndexableContentAndDeletesAfterRowDisappears() {
        when(products.indexSnapshot(42)).thenReturn(Optional.of(
                new ProductIndexSnapshot(42L, "Laptop", null, 10L, "Máy tính")));
        assertEquals(UPSERTED, sync.syncProduct(42));
        PointId originalId = points.keySet().iterator().next();
        assertTrue(originalId.hasUuid());
        assertEquals(42L, points.get(originalId).getPayloadOrThrow("product_id").getIntegerValue());
        assertFalse(points.get(originalId).getPayloadMap().containsKey("price"));
        assertFalse(points.get(originalId).getPayloadMap().containsKey("quantity"));
        assertEquals(SKIPPED, sync.syncProduct(42)); // identical projection after price/quantity changes
        verify(embeddings).embed("Tên sản phẩm: Laptop\nDanh mục: Máy tính\nMô tả: ");

        when(products.indexSnapshot(42)).thenReturn(
                Optional.of(new ProductIndexSnapshot(42L, "Laptop mới", null, 10L, "Máy tính")),
                Optional.of(new ProductIndexSnapshot(42L, "Laptop mới", "Nhẹ", 10L, "Máy tính")),
                Optional.of(new ProductIndexSnapshot(42L, "Laptop mới", "Nhẹ", 20L, "Laptop")),
                Optional.empty());
        assertEquals(UPSERTED, sync.syncProduct(42));
        assertEquals(UPSERTED, sync.syncProduct(42));
        assertEquals(UPSERTED, sync.syncProduct(42));
        assertEquals(1, points.size());
        assertEquals(20L, points.get(originalId).getPayloadOrThrow("category_id").getIntegerValue());
        assertEquals(DELETED, sync.syncProduct(42));
        assertEquals(SKIPPED, sync.syncProduct(42));
        assertTrue(points.isEmpty());
        verify(embeddings, times(4)).embed(anyString());
    }

    @Test
    void backfillUsesBatchesReportsFailuresAndCanResumeAfterQuotaReturns() {
        when(products.maxIndexId()).thenReturn(2L);
        when(products.indexIds(0, null, PageRequest.of(0, 1))).thenReturn(List.of(1L));
        when(products.indexIds(1, null, PageRequest.of(0, 1))).thenReturn(List.of(2L));
        when(products.indexSnapshot(1)).thenReturn(Optional.of(
                new ProductIndexSnapshot(1L, "Laptop", "", 1L, "Thiết bị")));
        when(products.indexSnapshot(2)).thenReturn(Optional.of(
                new ProductIndexSnapshot(2L, "Chuột", "", 1L, "Thiết bị")));
        String mouse = "Tên sản phẩm: Chuột\nDanh mục: Thiết bị\nMô tả: ";
        when(embeddings.embed(mouse)).thenThrow(new ProductEmbeddingService.ProviderFailure("Gemini HTTP 429", true));

        var report = sync.syncAll();
        assertEquals(1, report.succeeded());
        assertEquals(1, report.failed());
        assertEquals(2, report.failures().get(0).productId());
        verify(embeddings, times(2)).embed(mouse);

        doReturn(new float[768]).when(embeddings).embed(mouse);
        var retried = sync.syncAll();
        assertEquals(1, retried.succeeded());
        assertEquals(1, retried.skipped());
        assertEquals(0, retried.failed());
        assertEquals(2, points.size());
    }

    @Test
    void qdrantOutageRetriesWithoutCallingGemini() {
        when(products.indexSnapshot(42)).thenReturn(Optional.of(
                new ProductIndexSnapshot(42L, "Laptop", "", 1L, "Thiết bị")));
        when(index.ensureCollection(true)).thenThrow(new QdrantProductIndex.Failure(Status.Code.UNAVAILABLE));
        assertThrows(QdrantProductIndex.Failure.class, () -> sync.syncProduct(42));
        verify(index, times(2)).ensureCollection(true);
        verifyNoInteractions(embeddings);
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusesIncompatibleExistingCollectionWithoutReplacingIt() {
        ObjectProvider<QdrantClient> clients = mock(ObjectProvider.class);
        QdrantClient client = mock(QdrantClient.class);
        when(clients.getObject()).thenReturn(client);
        when(client.collectionExistsAsync("eshop_products_v1", Duration.ofSeconds(5))).thenReturn(immediateFuture(true));
        when(client.getCollectionInfoAsync("eshop_products_v1", Duration.ofSeconds(5))).thenReturn(immediateFuture(
                CollectionInfo.newBuilder().setConfig(CollectionConfig.newBuilder().setParams(
                        CollectionParams.newBuilder().setVectorsConfig(VectorsConfig.newBuilder().setParams(
                                VectorParams.newBuilder().setDistance(Distance.Cosine).setSize(3072))))).build()));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> new QdrantProductIndex(clients).ensureCollection(true)).getStatusCode().value());
        verify(client).collectionExistsAsync(anyString(), any());
        verify(client).getCollectionInfoAsync(anyString(), any());
        verifyNoMoreInteractions(client);
    }

    @Test
    void refusesToDeletePointNotOwnedByThisIndex() {
        when(index.find(any())).thenReturn(Optional.of(RetrievedPoint.newBuilder()
                .putPayload("product_id", value(42L)).build()));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> sync.syncProduct(42))
                .getStatusCode().value());
        verify(index, never()).delete(any());
    }
}

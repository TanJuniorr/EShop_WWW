package com.example.eshop.service.ai;

import com.example.eshop.infrastructure.ai.QdrantProductIndex;

import com.example.eshop.repository.ProductRepository;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductSemanticSearchServiceTest {
    private final ProductEmbeddingService embeddings = mock(ProductEmbeddingService.class);
    private final QdrantProductIndex index = mock(QdrantProductIndex.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductSemanticSearchService search =
            new ProductSemanticSearchService(embeddings, index, products, 5, 0.25f, 24, 800, 1, 2);

    @Test
    void rejectsInvalidInputBeforeCallingExternalServices() {
        assertStatus(400, () -> search.search(null, null));
        assertStatus(400, () -> search.search(" \u00a0 ", null));
        assertStatus(400, () -> search.search("x".repeat(25), null));
        assertStatus(400, () -> search.search("Laptop", 0L));
        verifyNoInteractions(embeddings, index, products);
    }

    @Test
    void normalizesVietnameseAndCapsContinuousRequests() {
        float[] vector = new float[768];
        when(embeddings.embed("điện thoại")).thenReturn(vector);
        when(index.search(vector, 15, 0.25f, 3L, Duration.ofMillis(800))).thenReturn(List.of());

        assertTrue(search.search("  đie\u0323\u0302n   thoại ", 3L).isEmpty());
        assertTrue(search.search("điện thoại", 3L).isEmpty());
        assertStatus(429, () -> search.search("dien thoai", 3L));
        verify(embeddings, times(2)).embed("điện thoại");
        verify(index, times(2)).search(vector, 15, 0.25f, 3L, Duration.ofMillis(800));
        verifyNoInteractions(products);
    }

    @Test
    void failsClearlyWhenGeminiOrQdrantIsUnavailableWithoutQueryingSql() {
        when(embeddings.embed("Laptop")).thenThrow(new ProductEmbeddingService.ProviderFailure("quota", true));
        assertStatus(503, () -> search.search("Laptop", null));
        verifyNoInteractions(index);

        float[] vector = new float[768];
        when(embeddings.embed("Máy tính")).thenReturn(vector);
        when(index.search(vector, 15, 0.25f, null, Duration.ofMillis(800)))
                .thenThrow(new QdrantProductIndex.Failure(Status.Code.DEADLINE_EXCEEDED));
        assertStatus(503, () -> search.search("Máy tính", null));
        verifyNoInteractions(products);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Điện thoại", "iPhone", "iPhone 15", "Điện thoại Samsung",
            "điện thoại chip A16", "điện thoại AMOLED", "điện thoại trên 5 triệu",
            "Sản phẩm từ 1 đến 3 triệu", "Dưới 5 triệu", "Laptop còn hàng",
            "Điện thoại giá thấp nhất", "Điện thoại từ 10 đến 5 triệu"
    })
    void everyFormerRuleUsesTheCompleteQueryAndVectorSearch(String query) {
        var search = new ProductSemanticSearchService(embeddings, index, products,
                5, .25f, 100, 800, 1, 10);
        var vector = new float[768];
        when(embeddings.embed(query)).thenReturn(vector);
        when(index.search(vector, 15, .25f, null, Duration.ofMillis(800))).thenReturn(List.of());

        var result = search.search(query, null, 0);

        assertEquals(ProductSemanticSearchService.SearchMode.VECTOR, result.mode());
        assertNull(result.filters().categoryId());
        verify(embeddings).embed(query);
        verify(index).search(vector, 15, .25f, null, Duration.ofMillis(800));
        verifyNoInteractions(products);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Điện thoại", "iPhone", "chip A16", "Dưới 5 triệu"})
    void quotaFailureNeverFallsBackToCatalogRules(String query) {
        when(embeddings.embed(query)).thenThrow(new ProductEmbeddingService.ProviderFailure("quota", true));

        assertStatus(503, () -> search.search(query, null));

        verifyNoInteractions(index, products);
    }

    private static void assertStatus(int expected, Runnable call) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, call::run).getStatusCode().value());
    }
}

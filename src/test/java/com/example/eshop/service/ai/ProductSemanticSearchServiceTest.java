package com.example.eshop.service.ai;

import com.example.eshop.infrastructure.ai.QdrantProductIndex;

import com.example.eshop.entity.Category;
import com.example.eshop.repository.CategoryRepository;
import com.example.eshop.repository.ProductRepository;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductSemanticSearchServiceTest {
    private final ProductEmbeddingService embeddings = mock(ProductEmbeddingService.class);
    private final QdrantProductIndex index = mock(QdrantProductIndex.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final ProductSemanticSearchService search =
            new ProductSemanticSearchService(embeddings, index, products, categories, 5, 0.25f, 24, 800, 1, 2);

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

    @Test
    void scopesOnlyClearLeadingCategoriesAndRespectsExplicitFilters() {
        var search = new ProductSemanticSearchService(embeddings, index, products, categories,
                5, .25f, 100, 800, 1, 10);
        var deadline = Duration.ofMillis(800);
        when(categories.findAllByOrderByNameAsc()).thenReturn(List.of(
                category(1L, "Điện thoại"), category(2L, "Chuột"), category(3L, "Laptop")));
        when(embeddings.embed(anyString())).thenAnswer(call -> new float[768]);

        search.search("Toi can dien thoai phu hop cho sinh vien", null);
        search.search("Chuột không dây cho laptop", null);
        search.search("Ốp lưng điện thoại", null);
        search.search("Điện thoại hoặc laptop", null);
        search.search("Điện thoại", 3L);

        var calls = inOrder(index);
        calls.verify(index).search(any(float[].class), eq(15), eq(.25f), eq(1L), eq(deadline));
        calls.verify(index).search(any(float[].class), eq(15), eq(.25f), eq(2L), eq(deadline));
        calls.verify(index, times(2)).search(any(float[].class), eq(15), eq(.25f), isNull(), eq(deadline));
        calls.verify(index).search(any(float[].class), eq(15), eq(.25f), eq(3L), eq(deadline));
    }

    private static Category category(long id, String name) {
        var category = new Category();
        category.setId(id);
        category.setName(name);
        return category;
    }

    private static void assertStatus(int expected, Runnable call) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, call::run).getStatusCode().value());
    }
}

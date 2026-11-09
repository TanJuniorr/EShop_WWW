package com.example.eshop.ai;

import com.google.common.util.concurrent.Futures;
import com.example.eshop.entity.Category;
import com.example.eshop.entity.Product;
import com.example.eshop.repository.CategoryRepository;
import com.example.eshop.service.ProductService;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.QdrantOuterClass.HealthCheckReply;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("ai-dev")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eshop_ai_dev;MODE=MariaDB;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-only",
        "spring.jpa.show-sql=false",
        "spring.sql.init.mode=never",
        "spring.ai.google.genai.embedding.api-key=",
        "app.jwt.secret="
})
class AiDiagnosticsTest {
    private static final String VIETNAMESE_TEXT = "Laptop phù hợp cho sinh viên lập trình";

    @Autowired private WebApplicationContext context;
    @MockitoBean private QdrantClient qdrant;
    @MockitoBean private ProductVectorSyncService sync;
    @Autowired private ProductService products;
    @Autowired private CategoryRepository categories;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    @WithMockUser(roles = "SELLER")
    void onlyCommitsScheduleProductIndexChanges() {
        Category category = new Category();
        category.setName("Index transaction fixtures");
        long categoryId = categories.save(category).getId();
        TransactionTemplate tx = new TransactionTemplate(transactions);

        long rolledBack = tx.execute(status -> {
            long id = products.save(product("Rolled back"), categoryId).getId();
            status.setRollbackOnly();
            return id;
        });
        long committed = products.save(product("Committed"), categoryId).getId();
        verify(sync, timeout(2000)).syncProduct(committed);
        verify(sync, never()).syncProduct(rolledBack);

        tx.executeWithoutResult(status -> {
            products.delete(committed);
            status.setRollbackOnly();
        });
        verify(sync, after(300).times(1)).syncProduct(committed);
        products.delete(committed);
        verify(sync, timeout(2000).times(2)).syncProduct(committed);
    }

    private static Product product(String name) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(BigDecimal.TEN);
        product.setQuantity(1);
        return product;
    }

    @Test
    void missingGeminiKeyDoesNotPreventShopOrQdrantDiagnostic() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var seller = user("seller").roles("SELLER");
        String qdrantPath = "/api/dev/ai/qdrant";
        String embeddingPath = "/api/dev/ai/embedding";

        mvc.perform(get("/api/products")).andExpect(status().isOk());
        mvc.perform(get(qdrantPath)).andExpect(status().isUnauthorized());
        mvc.perform(get(qdrantPath).with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(post(embeddingPath).with(seller).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(embeddingPath).with(seller).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + VIETNAMESE_TEXT + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Chưa cấu hình GEMINI_API_KEY."));

        when(qdrant.healthCheckAsync(Duration.ofSeconds(3))).thenReturn(
                Futures.immediateFuture(HealthCheckReply.newBuilder().setVersion("1.19.2").build()));
        mvc.perform(get(qdrantPath).with(seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        when(qdrant.healthCheckAsync(Duration.ofSeconds(3))).thenReturn(
                Futures.immediateFailedFuture(new IllegalStateException("unavailable")));
        mvc.perform(get(qdrantPath).with(seller)).andExpect(status().isServiceUnavailable());
    }

    @Test
    @SuppressWarnings("unchecked")
    void embeddingRejectsUnexpectedOrNonFiniteVectors() {
        var model = mock(EmbeddingModel.class);
        ObjectProvider<EmbeddingModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(model);
        var service = new ProductEmbeddingService(models);

        float[] vector = new float[768];
        vector[0] = 1f;
        when(model.embed(VIETNAMESE_TEXT)).thenReturn(vector);
        assertEquals(768, service.embed(VIETNAMESE_TEXT).length);

        when(model.embed(VIETNAMESE_TEXT)).thenReturn(new float[0]);
        assertEquals(502, assertThrows(ResponseStatusException.class,
                () -> service.embed(VIETNAMESE_TEXT)).getStatusCode().value());

        vector[0] = Float.NaN;
        when(model.embed(VIETNAMESE_TEXT)).thenReturn(vector);
        assertEquals(502, assertThrows(ResponseStatusException.class,
                () -> service.embed(VIETNAMESE_TEXT)).getStatusCode().value());
    }
}

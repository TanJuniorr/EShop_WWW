package com.example.eshop.service.ai;

import com.example.eshop.infrastructure.ai.QdrantProductIndex;

import com.example.eshop.entity.Category;
import com.example.eshop.entity.Product;
import com.example.eshop.repository.CategoryRepository;
import com.example.eshop.repository.ProductRepository;
import io.qdrant.client.grpc.Points.ScoredPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static io.qdrant.client.ValueFactory.value;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("ai-dev")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eshop_semantic;MODE=MariaDB;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-only",
        "spring.jpa.show-sql=false",
        "spring.sql.init.mode=never",
        "spring.ai.google.genai.embedding.api-key=",
        "app.jwt.secret="
})
class ProductSemanticSearchWebTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ProductRepository products;
    @Autowired private CategoryRepository categories;
    @MockitoBean private ProductEmbeddingService embeddings;
    @MockitoBean private QdrantProductIndex index;

    @Test
    void anonymousSearchPreservesRankingAndUsesCurrentDatabaseFields() throws Exception {
        Category category = new Category();
        category.setName("Laptop");
        category = categories.save(category);
        Product first = save(category, "Máy phổ thông", 4);
        Product second = save(category, "Máy chuyên lập trình", 0);
        String text = "Laptop cho sinh viên";
        float[] vector = new float[768];
        when(embeddings.embed(text)).thenReturn(vector);
        var hits = List.of(point(99_999L, .99f), point(second.getId(), .90f), point(first.getId(), .72f),
                point(second.getId(), .65f));
        when(index.search(vector, 15, .55f, category.getId(), Duration.ofSeconds(3))).thenReturn(hits);
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mvc.perform(get("/api/products/semantic-search").param("q", text))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second.getId().intValue()))
                .andExpect(jsonPath("$[0].price").value(6500000))
                .andExpect(jsonPath("$[0].inStock").value(false))
                .andExpect(jsonPath("$[0].score").value(.9))
                .andExpect(jsonPath("$[0].vector").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(first.getId().intValue()));
        mvc.perform(get("/products").param("keyword", text))
                .andExpect(status().isOk()).andExpect(view().name("shop/index"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("Tìm kiếm AI")))
                .andExpect(content().string(containsString("maxlength=\"300\"")))
                .andExpect(content().string(not(containsString("name=\"mode\""))))
                .andExpect(content().string(containsString("Máy chuyên lập trình")))
                .andExpect(content().string(containsString("Hết hàng")));
        mvc.perform(get("/api/products/semantic-search").param("q", " "))
                .andExpect(status().isBadRequest());

        when(embeddings.embed(first.getName())).thenThrow(new ProductEmbeddingService.ProviderFailure("test", true));
        mvc.perform(get("/products").param("mode", "standard").param("keyword", first.getName()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("Tìm kiếm AI tạm thời không hoạt động")))
                .andExpect(content().string(not(containsString("Tìm kiếm thông thường"))))
                .andExpect(content().string(not(containsString("class=\"card h-100 shadow-sm\""))));
    }

    @Test
    void phoneQueryExcludesRobotEvenIfItsVectorScoresHigher() throws Exception {
        var phones = saveCategory("Điện thoại");
        var appliances = saveCategory("Gia dụng thông minh");
        var phone = save(phones, "iPhone 15", 4);
        var robot = save(appliances, "Robot hút bụi Xiaomi E10", 3);
        String query = "điện thoại phù hợp cho sinh viên";
        float[] vector = new float[768];
        when(embeddings.embed(query)).thenReturn(vector);
        when(index.search(vector, 15, .55f, phones.getId(), Duration.ofSeconds(3)))
                .thenReturn(List.of(point(robot.getId(), .91f), point(phone.getId(), .83f)));

        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/products/semantic-search").param("q", query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(phone.getId().intValue()));
        mvc.perform(get("/products").param("keyword", query).param("categoryId", phones.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("iPhone 15")))
                .andExpect(content().string(not(containsString("Robot hút bụi Xiaomi E10"))));
    }

    private Category saveCategory(String name) {
        var category = new Category();
        category.setName(name);
        return categories.save(category);
    }

    private Product save(Category category, String name, int stock) {
        Product p = new Product();
        p.setCategory(category);
        p.setName(name);
        p.setPrice(BigDecimal.valueOf(6_500_000));
        p.setQuantity(stock);
        return products.save(p);
    }

    private static ScoredPoint point(long id, float score) {
        return ScoredPoint.newBuilder().putPayload("product_id", value(id)).setScore(score).build();
    }
}

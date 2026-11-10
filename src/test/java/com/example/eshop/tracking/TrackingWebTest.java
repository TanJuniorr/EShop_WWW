package com.example.eshop.tracking;

import com.example.eshop.ai.ProductEmbeddingService;
import com.example.eshop.entity.Category;
import com.example.eshop.entity.Product;
import com.example.eshop.entity.User;
import com.example.eshop.repository.CategoryRepository;
import com.example.eshop.repository.ProductRepository;
import com.example.eshop.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.example.eshop.tracking.TrackingEventType.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("ai-dev")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eshop_tracking;MODE=MariaDB;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-only",
        "spring.jpa.show-sql=false",
        "spring.sql.init.mode=never",
        "spring.ai.google.genai.embedding.api-key=",
        "eshop.ai.product-sync.auto-enabled=false",
        "eshop.tracking.enabled=true",
        "app.jwt.secret="
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackingWebTest {
    @Autowired private WebApplicationContext context;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcClient jdbc;
    @Autowired private TrackingService tracking;
    @MockitoSpyBean private TrackingRepository store;
    @Autowired private ProductRepository products;
    @Autowired private CategoryRepository categories;
    @Autowired private UserRepository users;
    @MockitoBean private ChatModel model;
    @MockitoBean private ProductEmbeddingService embeddings;
    private MockMvc mvc;
    private Product phone;
    private Product headset;
    private long customerId;

    @DynamicPropertySource
    static void isolatedMariaDb(DynamicPropertyRegistry properties) {
        String port = System.getenv("ESHOP_TRACKING_IT_PORT");
        if (port == null) {
            return;
        }
        String password = System.getenv("ESHOP_TRACKING_IT_PASSWORD");
        if (!port.matches("[1-9]\\d{4}") || Integer.parseInt(port) > 65_535 || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Use a disposable MariaDB fixture on a separate high port.");
        }
        properties.add("spring.datasource.url", () -> "jdbc:mariadb://127.0.0.1:" + port + "/eshop_tracking_it");
        properties.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
        properties.add("spring.datasource.username", () -> "root");
        properties.add("spring.datasource.password", () -> password);
    }

    @BeforeAll
    void setup() {
        new ResourceDatabasePopulator(new FileSystemResource("database/migrations/V007__user_tracking.sql")).execute(dataSource);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        phone = product("Điện thoại", "Galaxy", 6_000_000);
        headset = product("Tai nghe", "AirPods", 5_990_000);
        var customer = new User();
        customer.setUsername("tracking_customer");
        customer.setFullName("Tracking Customer");
        customer.setEmail("tracking-customer@example.test");
        customer.setPassword("unused-in-security-test");
        customerId = users.save(customer).getId();
    }

    @AfterEach
    void clearAnalytics() throws Exception {
        tracking.awaitWrites();
        jdbc.sql("delete from tracking_events").update();
        jdbc.sql("delete from tracking_sessions").update();
    }

    @Test
    void collectsOnlyAfterConsentAndStopsImmediatelyOnWithdrawal() throws Exception {
        var browser = new MockHttpSession();
        mvc.perform(get("/products").session(browser))
                .andExpect(content().string(containsString("data-consent=\"UNSET\"")));
        mvc.perform(get("/products/{id}", phone.getId()).session(browser)).andExpect(status().isOk());
        open(browser, UUID.randomUUID()).andExpect(status().isForbidden());
        choice(browser, false).andExpect(jsonPath("$.consent").value("DECLINED"));
        mvc.perform(get("/").session(browser)).andExpect(status().isOk());
        tracking.awaitWrites();
        assertEquals(0L, rows("select count(*) from tracking_sessions"));
        assertEquals(0L, rows("select count(*) from tracking_events"));

        choice(browser, true).andExpect(jsonPath("$.consent").value("ACCEPTED"));
        mvc.perform(get("/").session(browser).param("token", "private")).andExpect(status().isOk());
        mvc.perform(get("/products/{id}", phone.getId()).session(browser).param("private", "value")).andExpect(status().isOk());
        mvc.perform(get("/products/{id}", 999999).session(browser)).andExpect(status().isNotFound());
        tracking.awaitWrites();
        assertEquals(2L, count(PAGE_VIEW));
        assertEquals(1L, count(PRODUCT_VIEW));
        assertEquals(0L, rows("select count(*) from tracking_events where page_path like '%?%'"));
        choice(browser, false).andExpect(jsonPath("$.consent").value("DECLINED"));
        mvc.perform(get("/products/{id}", phone.getId()).session(browser)).andExpect(status().isOk());
        open(browser, UUID.randomUUID()).andExpect(status().isForbidden());
        tracking.awaitWrites();
        assertEquals(1L, count(PRODUCT_VIEW));
        assertEquals(1L, rows("select count(*) from tracking_sessions where revoked_at is not null and ended_at is not null"));
    }

    @Test
    void protectsConsentAndEventsWithCsrfBoundsAndSessionOwnership() throws Exception {
        var browser = new MockHttpSession();
        mvc.perform(post("/tracking/consent").session(browser).contentType(MediaType.APPLICATION_JSON).content("{\"allowed\":true}"))
                .andExpect(status().isForbidden());
        choice(browser, true);
        var offer = offer(mvc.perform(get("/products").session(browser)));
        mvc.perform(post("/tracking/events").session(browser).contentType(MediaType.APPLICATION_JSON)
                .content(event(UUID.randomUUID(), "CHATBOT_OPEN", null, null))).andExpect(status().isForbidden());
        mvc.perform(post("/tracking/events").session(browser).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(1025))).andExpect(status().isPayloadTooLarge());
        send(browser, event(UUID.randomUUID(), "ORDER_COMPLETED", offer, phone.getId())).andExpect(status().isBadRequest());
        send(browser, event(UUID.randomUUID(), "PRODUCT_CLICK", offer, 999999L)).andExpect(status().isBadRequest());
        var other = new MockHttpSession();
        choice(other, true);
        send(other, event(UUID.randomUUID(), "PRODUCT_CLICK", offer, phone.getId())).andExpect(status().isBadRequest());
        var id = UUID.randomUUID();
        send(browser, event(id, "PRODUCT_CLICK", offer, phone.getId())).andExpect(status().isAccepted());
        send(browser, event(id, "PRODUCT_CLICK", offer, headset.getId())).andExpect(status().isAccepted());
        tracking.awaitWrites();
        assertEquals(1L, count(PRODUCT_CLICK));
        assertEquals(0L, count(PRODUCT_VIEW));
        assertEquals(0L, count(ORDER_COMPLETED));
    }

    @Test
    void aiSearchRecordsAppliedFiltersEmptyResultsErrorsAndOnlyRealClicks() throws Exception {
        var browser = new MockHttpSession();
        choice(browser, true);
        String query = "Tìm các điện thoại có giá trên 5 triệu";
        var search = mvc.perform(get("/products").session(browser).param("keyword", query))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Galaxy")))
                .andExpect(content().string(not(containsString("AirPods"))));
        var resultOffer = offer(search);
        send(browser, event(UUID.randomUUID(), "PRODUCT_CLICK", resultOffer, headset.getId())).andExpect(status().isBadRequest());
        send(browser, event(UUID.randomUUID(), "PRODUCT_CLICK", resultOffer, phone.getId())).andExpect(status().isAccepted());
        mvc.perform(get("/products").session(browser).param("keyword", "Điện thoại dưới 1 triệu"))
                .andExpect(status().isOk());
        String sensitive = "Laptop private@example.test";
        when(embeddings.embed(sensitive)).thenThrow(new IllegalStateException("provider down"));
        mvc.perform(get("/products").session(browser).param("keyword", sensitive)).andExpect(status().isServiceUnavailable());
        tracking.awaitWrites();
        assertEquals(3L, count(AI_SEARCH));
        assertEquals(1L, count(AI_SEARCH_RESULT_CLICK));
        assertEquals(1L, count(PRODUCT_CLICK));
        assertEquals(1L, rows("""
                select count(*) from tracking_events where event_type='AI_SEARCH' and search_mode='FILTERS'
                  and search_result_count=1 and min_price=5000000 and min_inclusive=false and category_id is not null
                """));
        assertEquals(1L, rows("select count(*) from tracking_events where search_status='OK' and search_result_count=0"));
        assertEquals(1L, rows("select count(*) from tracking_events where search_status='ERROR' and search_keyword is null"));
        assertEquals(1L, rows("select count(*) from tracking_events where event_type='AI_SEARCH_RESULT_CLICK' and result_position=1"));
        mvc.perform(get("/admin/analytics/data").with(seller()).param("period", "today"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.search.emptyRate").value(50.0))
                .andExpect(jsonPath("$.search.clickRate").value(100.0))
                .andExpect(jsonPath("$.searchProducts[0].id").value(phone.getId().intValue()));
    }

    @Test
    void chatbotOpenMessageAndProductClickContainNoConversationText() throws Exception {
        var browser = new MockHttpSession();
        choice(browser, true);
        var openId = UUID.randomUUID();
        open(browser, openId).andExpect(status().isAccepted());
        open(browser, openId).andExpect(status().isAccepted());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("1 PRICE")))));
        var response = mvc.perform(post("/ai/chat").session(browser).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Điện thoại trên 5 triệu\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.products[0].id").value(phone.getId().intValue()))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(response, "$.trackingId"));
        send(browser, event(UUID.randomUUID(), "PRODUCT_CLICK", id, phone.getId())).andExpect(status().isAccepted());
        tracking.awaitWrites();
        assertEquals(1L, count(CHATBOT_OPEN));
        assertEquals(1L, count(CHATBOT_MESSAGE_SENT));
        assertEquals(1L, count(CHATBOT_PRODUCT_CLICK));
        assertEquals(0L, rows("select count(*) from tracking_events where search_keyword is not null"));
        mvc.perform(get("/admin/analytics/data").with(seller()).param("period", "today"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chat.engagementRate").value(100.0))
                .andExpect(jsonPath("$.chatProducts[0].id").value(phone.getId().intValue()));
    }

    @Test
    void switchingToAccountRequiresSeparateConsentAndLogoutClosesVisit() throws Exception {
        var browser = new MockHttpSession();
        choice(browser, true);
        open(browser, UUID.randomUUID()).andExpect(status().isAccepted());
        mvc.perform(get("/products").session(browser).with(customer()))
                .andExpect(content().string(containsString("data-consent=\"UNSET\"")));
        mvc.perform(json("/tracking/consent", browser, "{\"allowed\":true}").with(customer())).andExpect(status().isOk());
        mvc.perform(get("/products").session(browser).with(customer())).andExpect(status().isOk());
        mvc.perform(post("/logout").session(browser).with(customer()).with(csrf())).andExpect(status().is3xxRedirection());
        tracking.awaitWrites();
        assertEquals(2L, rows("select count(*) from tracking_sessions where ended_at is not null"));
        assertEquals(1L, rows("select count(*) from tracking_sessions where user_id is null"));
        assertEquals(1L, rows("select count(*) from tracking_sessions where user_id = " + customerId));
    }

    @Test
    void checkoutAndCompletionTrackOnlyActualTransitionsAndAttributeOriginalSession() throws Exception {
        var browser = new MockHttpSession();
        mvc.perform(json("/tracking/consent", browser, "{\"allowed\":true}").with(customer())).andExpect(status().isOk());
        mvc.perform(get("/products/{id}", phone.getId()).session(browser).with(customer())).andExpect(status().isOk());
        mvc.perform(post("/cart/add").session(browser).with(customer()).with(csrf())
                .param("productId", phone.getId().toString()).param("quantity", "999"))
                .andExpect(redirectedUrl("/products/" + phone.getId()));
        mvc.perform(post("/cart/add").session(browser).with(customer()).with(csrf())
                .param("productId", phone.getId().toString())).andExpect(redirectedUrl("/cart"));
        mvc.perform(post("/checkout").session(browser).with(customer()).with(csrf())).andExpect(status().isOk());
        var orderUrl = mvc.perform(post("/checkout").session(browser).with(customer()).with(csrf())
                        .param("customerName", "Tracking Customer").param("phone", "0900000000")
                        .param("address", "H2 test address").param("paymentMethod", "COD"))
                .andExpect(redirectedUrlPattern("/orders/*")).andReturn().getResponse().getRedirectedUrl();
        sellerStatus(orderUrl, "SHIPPING");
        tracking.awaitWrites();
        assertEquals(1L, count(ADD_TO_CART));
        assertEquals(1L, count(ORDER_PLACED));
        assertEquals(0L, count(ORDER_COMPLETED));
        mvc.perform(post("/logout").session(browser).with(customer()).with(csrf())).andExpect(status().is3xxRedirection());
        sellerStatus(orderUrl, "DONE");
        sellerStatus(orderUrl, "DONE");
        sellerStatus(orderUrl, "SHIPPING");
        sellerStatus(orderUrl, "DONE");
        tracking.awaitWrites();
        assertEquals(1L, count(ORDER_COMPLETED));
        assertEquals(1L, rows("select count(distinct session_id) from tracking_events where order_id is not null"));
        mvc.perform(get("/admin/analytics/data").with(seller()).param("period", "today"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.funnel.viewed").value(1))
                .andExpect(jsonPath("$.funnel.carted").value(1)).andExpect(jsonPath("$.funnel.placed").value(1))
                .andExpect(jsonPath("$.funnel.completed").value(1));
    }

    @Test
    void analyticsRequiresSellerAndUsesLocalDateBoundariesAndOrderedFunnel() throws Exception {
        mvc.perform(get("/admin/analytics")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/analytics/data").with(customer())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/analytics").with(seller())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Phễu chuyển đổi")));
        mvc.perform(get("/admin/analytics/data").with(seller()).param("period", "custom"))
                .andExpect(status().isBadRequest());
        var date = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1);
        LocalDateTime midnight = LocalDateTime.ofInstant(date.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(), ZoneOffset.UTC);
        UUID session = UUID.randomUUID();
        store.start(session, null, midnight.minusSeconds(1));
        store.append(session, List.of(TrackingEvent.page(PAGE_VIEW, null, "/", midnight.minusSeconds(1))));
        store.append(session, List.of(TrackingEvent.page(PAGE_VIEW, null, "/", midnight)));
        store.append(session, List.of(TrackingEvent.page(ADD_TO_CART, phone.getId(), null, midnight.plusSeconds(1))));
        store.append(session, List.of(TrackingEvent.page(PRODUCT_VIEW, phone.getId(), null, midnight.plusSeconds(2))));
        store.append(session, List.of(TrackingEvent.order(ORDER_PLACED, 9921L, midnight.plusSeconds(3))));
        store.append(session, List.of(TrackingEvent.page(PAGE_VIEW, null, "/", midnight.plusDays(1))));
        mvc.perform(get("/admin/analytics/data").with(seller()).param("period", "custom")
                        .param("start", date.toString()).param("end", date.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.counts.PAGE_VIEW").value(1))
                .andExpect(jsonPath("$.summary.visitors.sessions").value(1))
                .andExpect(jsonPath("$.summary.visitors.anonymousSessions").value(1))
                .andExpect(jsonPath("$.daily[0].visits").value(1))
                .andExpect(jsonPath("$.funnel.viewed").value(1)).andExpect(jsonPath("$.funnel.carted").value(0))
                .andExpect(jsonPath("$.funnel.placed").value(0)).andExpect(jsonPath("$.funnel.completed").value(0));
    }

    @Test
    void retentionRemovesOldRowsAndRevokedVisitsRejectFutureCompletion() {
        var now = TrackingService.now();
        var old = UUID.randomUUID();
        var revoked = UUID.randomUUID();
        store.start(old, null, now.minusDays(31));
        store.append(old, List.of(TrackingEvent.page(PAGE_VIEW, null, "/", now.minusDays(31))));
        store.start(revoked, null, now.minusMinutes(2));
        store.append(revoked, List.of(TrackingEvent.order(ORDER_PLACED, 9901L, now.minusMinutes(1))));
        store.end(revoked, now, true);
        store.complete(TrackingEvent.order(ORDER_COMPLETED, 9901L, now));
        store.purge(now, now.minusDays(30), now.minusMinutes(30));
        assertEquals(0L, count(PAGE_VIEW));
        assertEquals(1L, count(ORDER_PLACED));
        assertEquals(0L, count(ORDER_COMPLETED));
        assertEquals(1L, rows("select count(*) from tracking_sessions"));
    }

    @Test
    void throttlesClientEventsAndRequiresExplicitBooleanConsent() throws Exception {
        var browser = new MockHttpSession();
        mvc.perform(json("/tracking/consent", browser, "{\"allowed\":\"true\"}")).andExpect(status().isBadRequest());
        choice(browser, true);
        for (int i = 0; i < 60; i++) {
            open(browser, UUID.randomUUID()).andExpect(status().isAccepted());
        }
        open(browser, UUID.randomUUID()).andExpect(status().isTooManyRequests());
        tracking.awaitWrites();
        assertEquals(60L, count(CHATBOT_OPEN));
    }

    @Test
    void searchAndChatContinueWhenTheTrackingWriterCannotPersistEvents() throws Exception {
        var browser = new MockHttpSession();
        choice(browser, true);
        doThrow(new DataAccessResourceFailureException("tracking unavailable")).when(store).append(any(UUID.class), anyList());
        mvc.perform(get("/products").session(browser).param("keyword", "Điện thoại trên 5 triệu"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Galaxy")));
        mvc.perform(json("/ai/chat", browser, "{\"message\":\"Xin chào\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(containsString("Chào bạn")));
        tracking.awaitWrites();
        assertEquals(0L, rows("select count(*) from tracking_events"));
    }

    private ResultActions choice(MockHttpSession session, boolean allowed) throws Exception {
        return mvc.perform(json("/tracking/consent", session, "{\"allowed\":" + allowed + "}")).andExpect(status().isOk());
    }

    private ResultActions open(MockHttpSession session, UUID id) throws Exception {
        return send(session, event(id, "CHATBOT_OPEN", null, null));
    }

    private ResultActions send(MockHttpSession session, String body) throws Exception {
        return mvc.perform(json("/tracking/events", session, body));
    }

    private static MockHttpServletRequestBuilder json(String path, MockHttpSession session, String body) {
        return post(path).session(session).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String event(UUID id, String type, UUID interaction, Long product) {
        return """
                {"eventId":"%s","eventType":"%s","interactionId":%s,"productId":%s}
                """.formatted(id, type, interaction == null ? "null" : "\"" + interaction + "\"", product);
    }

    private static UUID offer(ResultActions page) throws Exception {
        var id = (UUID) page.andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("trackingOffer");
        assertNotNull(id);
        return id;
    }

    private void sellerStatus(String orderUrl, String status) throws Exception {
        mvc.perform(post("/admin" + orderUrl + "/status").with(seller()).with(csrf()).param("status", status))
                .andExpect(redirectedUrl("/admin" + orderUrl));
    }

    private long count(TrackingEventType type) {
        return jdbc.sql("select count(*) from tracking_events where event_type=:type")
                .param("type", type.name()).query(Long.class).single();
    }

    private long rows(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private Product product(String categoryName, String name, long price) {
        var category = new Category();
        category.setName(categoryName);
        var product = new Product();
        product.setCategory(categories.save(category));
        product.setName(name);
        product.setPrice(BigDecimal.valueOf(price));
        product.setQuantity(10);
        return products.save(product);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor customer() {
        return user("tracking_customer").roles("CUSTOMER");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor seller() {
        return user("tracking_seller").roles("SELLER");
    }
}

package com.example.eshop;

import com.example.eshop.entity.Category;
import com.example.eshop.entity.Product;
import com.example.eshop.entity.Role;
import com.example.eshop.entity.User;
import com.example.eshop.repository.CategoryRepository;
import com.example.eshop.repository.ProductRepository;
import com.example.eshop.repository.UserRepository;
import com.example.eshop.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eshop_regression;MODE=MariaDB;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-only",
        "spring.jpa.show-sql=false",
        "spring.sql.init.mode=never",
        "app.jwt.secret="
})
class EshopRegressionTest {

    @Autowired private WebApplicationContext context;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void aiDiagnosticsAreAbsentInDefaultProfile() throws Exception {
        mvc.perform(get("/api/dev/ai/qdrant").with(user("seller").roles("SELLER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/products/semantic-search").param("q", "Laptop"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void registrationLoginRolesAndLogout() throws Exception {
        String password = UUID.randomUUID().toString();
        mvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"));
        mvc.perform(post("/register").with(csrf())
                        .param("username", "registered_customer")
                        .param("fullName", "Registered Customer")
                        .param("email", "registered@example.test")
                        .param("password", password)
                        .param("confirmPassword", password))
                .andExpect(redirectedUrl("/login"));

        Cookie customer = loginWeb("registered_customer", password, "/");
        mvc.perform(get("/").cookie(customer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("registered_customer")))
                .andExpect(content().string(not(containsString("/admin/products"))));
        mvc.perform(get("/admin/products").cookie(customer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/cart")).andExpect(redirectedUrl("/login"));

        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/logout").cookie(customer).session(session).with(csrf()))
                .andExpect(redirectedUrl("/?logout"))
                .andExpect(cookie().maxAge(JwtService.COOKIE_NAME, 0));
        assertTrue(session.isInvalid());
    }

    @Test
    void catalogSearchAndSellerProductCrud() throws Exception {
        Category category = category("Catalog fixtures");
        Product product = product(category, "Wireless Mouse");
        product(category, "Mechanical Keyboard");

        mvc.perform(get("/products").param("keyword", "LESS M")
                        .param("categoryId", category.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Wireless Mouse")))
                .andExpect(content().string(not(containsString("Mechanical Keyboard"))));
        mvc.perform(get("/products/{id}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Wireless Mouse")));
        mvc.perform(get("/api/products").param("keyword", "LESS M"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].categoryName").value(category.getName()));

        String password = createUser("catalog_seller", Role.ROLE_SELLER);
        Cookie seller = loginWeb("catalog_seller", password, "/admin/products");
        mvc.perform(get("/admin/products").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/product/list"));
        mvc.perform(get("/cart").cookie(seller)).andExpect(status().isForbidden());

        String token = loginApi("catalog_seller", password);
        String payload = """
                {"name":"%s","description":"Test product","price":45.50,"quantity":4,"categoryId":%d}
                """;
        mvc.perform(post("/api/products").servletPath("/api/products").cookie(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.formatted("Cookie-only product", category.getId())))
                .andExpect(status().isUnauthorized());
        String created = mvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.formatted("USB Cable", category.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(45.5))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(created, "$.id");
        mvc.perform(put("/api/products/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.formatted("USB-C Cable", category.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("USB-C Cable"));
        mvc.perform(delete("/api/products/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/products/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void cartCheckoutAndSellerOrderManagement() throws Exception {
        Product product = product(category("Order fixtures"), "Travel Charger");
        Cookie customer = loginWeb("order_customer", createUser("order_customer", Role.ROLE_CUSTOMER), "/");
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/cart/add").cookie(customer).session(session)
                        .param("productId", product.getId().toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/cart/add").cookie(customer).session(session).with(csrf())
                        .param("productId", product.getId().toString()).param("quantity", "2"))
                .andExpect(redirectedUrl("/cart"));
        mvc.perform(get("/cart").cookie(customer).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Travel Charger")));
        mvc.perform(get("/checkout").cookie(customer).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("checkout/form"));

        String orderUrl = mvc.perform(post("/checkout").cookie(customer).session(session).with(csrf())
                        .param("customerName", "Order Customer").param("phone", "0900000000")
                        .param("address", "Test address").param("paymentMethod", "COD"))
                .andExpect(redirectedUrlPattern("/orders/*"))
                .andReturn().getResponse().getRedirectedUrl();
        mvc.perform(get(orderUrl).cookie(customer)).andExpect(status().isOk());
        mvc.perform(get("/orders/my").cookie(customer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(orderUrl)));
        assertEquals(3, products.findById(product.getId()).orElseThrow().getQuantity());

        Cookie seller = loginWeb("order_seller", createUser("order_seller", Role.ROLE_SELLER), "/admin/products");
        mvc.perform(get("/admin/orders").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin" + orderUrl)));
        mvc.perform(get("/admin" + orderUrl).cookie(seller)).andExpect(status().isOk());
        mvc.perform(post("/admin" + orderUrl + "/status").cookie(seller).with(csrf())
                        .param("status", "CANCELLED"))
                .andExpect(redirectedUrl("/admin" + orderUrl));
        assertEquals(5, products.findById(product.getId()).orElseThrow().getQuantity());
    }

    private Cookie loginWeb(String username, String password, String redirect) throws Exception {
        Cookie cookie = mvc.perform(post("/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(redirectedUrl(redirect))
                .andExpect(cookie().httpOnly(JwtService.COOKIE_NAME, true))
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
        assertNotNull(cookie);
        return cookie;
    }

    private String loginApi(String username, String password) throws Exception {
        String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.accessToken");
    }

    private String createUser(String username, Role role) {
        String password = UUID.randomUUID().toString();
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setFullName(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(role);
        users.save(user);
        return password;
    }

    private Category category(String name) {
        Category category = new Category();
        category.setName(name);
        return categories.save(category);
    }

    private Product product(Category category, String name) {
        Product product = new Product();
        product.setName(name);
        product.setCategory(category);
        product.setPrice(BigDecimal.TEN);
        product.setQuantity(5);
        return products.save(product);
    }
}

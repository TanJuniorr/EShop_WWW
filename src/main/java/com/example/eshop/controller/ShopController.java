package com.example.eshop.controller;

import com.example.eshop.service.ai.ProductSemanticSearchService;
import com.example.eshop.service.CategoryService;
import com.example.eshop.service.ProductService;
import com.example.eshop.service.tracking.TrackingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class ShopController {

    private final ProductService productService;
    private final CategoryService categoryService;
    private final ObjectProvider<ProductSemanticSearchService> semanticSearch;
    private final TrackingService tracking;

    @GetMapping({"/", "/products"})
    public String index(@RequestParam(required = false) String keyword,
                        @RequestParam(required = false) Long categoryId,
                        @RequestParam(defaultValue = "0") int page,
                        Model model, HttpServletRequest request, HttpServletResponse response) {
        var semantic = semanticSearch.getIfAvailable();
        String query = StringUtils.hasText(keyword) ? keyword : null;
        model.addAttribute("aiMaxLength", semantic == null ? null : semantic.maxLength());
        var capture = tracking.capture(request);
        if (query != null) {
            response.setHeader("Cache-Control", "no-store");
            long started = System.nanoTime();
            try {
                if (semantic == null) {
                    throw ProductSemanticSearchService.unavailable();
                }
                var result = semantic.search(query, categoryId, 0);
                var cards = result.hits().stream()
                        .map(ProductSemanticSearchService.Hit::product).toList();
                model.addAttribute("cards", cards);
                model.addAttribute("resultCount", cards.size());
                if (capture != null) {
                    model.addAttribute("trackingOffer", tracking.search(capture, query, result,
                            cards.stream().map(p -> p.getId()).toList(), started));
                }
            } catch (ResponseStatusException failure) {
                tracking.search(capture, query, null, List.of(), started);
                response.setStatus(failure.getStatusCode().value());
                model.addAttribute("searchError", failure.getReason());
                model.addAttribute("cards", List.of());
                model.addAttribute("resultCount", 0);
            }
        } else {
            var products = productService.list(categoryId, page, 12);
            model.addAttribute("products", products);
            model.addAttribute("cards", products.getContent());
            model.addAttribute("resultCount", products.getTotalElements());
            if (capture != null) {
                model.addAttribute("trackingOffer", tracking.catalog(capture,
                        products.getContent().stream().map(p -> p.getId()).toList()));
            }
        }
        model.addAttribute("categories", categoryService.findAll());
        model.addAttribute("keyword", query);
        model.addAttribute("categoryId", categoryId);
        return "shop/index";
    }

    @GetMapping("/products/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("product", productService.findById(id));
        return "shop/detail";
    }
}

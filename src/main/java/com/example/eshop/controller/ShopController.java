package com.example.eshop.controller;

import com.example.eshop.ai.ProductSemanticSearchService;
import com.example.eshop.service.CategoryService;
import com.example.eshop.service.ProductService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class ShopController {

    private final ProductService productService;
    private final CategoryService categoryService;
    private final ObjectProvider<ProductSemanticSearchService> semanticSearch;

    @GetMapping({"/", "/products"})
    public String index(@RequestParam(required = false) String keyword,
                        @RequestParam(required = false) Long categoryId,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "standard") String mode,
                        Model model, HttpServletResponse response) {
        var semantic = semanticSearch.getIfAvailable();
        boolean aiMode = "ai".equals(mode);
        model.addAttribute("aiMode", aiMode);
        model.addAttribute("aiAvailable", semantic != null);
        model.addAttribute("aiMaxLength", semantic == null ? 300 : semantic.maxLength());
        if (aiMode) {
            response.setHeader("Cache-Control", "no-store");
            try {
                if (semantic == null) {
                    throw ProductSemanticSearchService.unavailable();
                }
                var cards = semantic.search(keyword, categoryId).stream()
                        .map(ProductSemanticSearchService.Hit::product).toList();
                model.addAttribute("cards", cards);
                model.addAttribute("resultCount", cards.size());
            } catch (ResponseStatusException failure) {
                response.setStatus(failure.getStatusCode().value());
                model.addAttribute("searchError", failure.getReason());
                model.addAttribute("cards", List.of());
                model.addAttribute("resultCount", 0);
            }
        } else {
            var products = productService.search(keyword, categoryId, page, 12);
            model.addAttribute("products", products);
            model.addAttribute("cards", products.getContent());
            model.addAttribute("resultCount", products.getTotalElements());
        }
        model.addAttribute("categories", categoryService.findAll());
        model.addAttribute("keyword", keyword);
        model.addAttribute("categoryId", categoryId);
        return "shop/index";
    }

    @GetMapping("/products/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("product", productService.findById(id));
        return "shop/detail";
    }
}

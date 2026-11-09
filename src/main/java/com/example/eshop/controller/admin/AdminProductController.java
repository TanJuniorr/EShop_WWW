package com.example.eshop.controller.admin;

import com.example.eshop.entity.Product;
import com.example.eshop.service.CategoryService;
import com.example.eshop.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;
    private final CategoryService categoryService;

    @GetMapping
    public String list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Long categoryId,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("products", productService.search(keyword, categoryId, page, 10));
        model.addAttribute("categories", categoryService.findAll());
        model.addAttribute("keyword", keyword);
        model.addAttribute("categoryId", categoryId);
        return "admin/product/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        Product p = new Product();
        p.setQuantity(0);
        return showForm(model, p, null);
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Product p = productService.findById(id);
        return showForm(model, p, p.getCategory().getId());
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("product") Product product,
                       BindingResult result,
                       @RequestParam(required = false) Long categoryId,
                       Model model, RedirectAttributes ra) {
        if (categoryId == null) {
            model.addAttribute("categoryError", "Vui lòng chọn danh mục");
        }
        if (result.hasErrors() || categoryId == null) {
            return showForm(model, product, categoryId);
        }
        productService.save(product, categoryId);
        ra.addFlashAttribute("message", "Đã lưu sản phẩm \"" + product.getName() + "\"");
        return "redirect:/admin/products";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            productService.delete(id);
            ra.addFlashAttribute("message", "Đã xoá sản phẩm");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/products";
    }

    private String showForm(Model model, Product product, Long selectedCategoryId) {
        model.addAttribute("product", product);
        model.addAttribute("categories", categoryService.findAll());
        model.addAttribute("selectedCategoryId", selectedCategoryId);
        return "admin/product/form";
    }
}

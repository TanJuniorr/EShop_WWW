package com.example.eshop.controller;

import com.example.eshop.cart.Cart;
import com.example.eshop.entity.Product;
import com.example.eshop.service.ProductService;
import com.example.eshop.service.tracking.TrackingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final Cart cart;
    private final ProductService productService;
    private final TrackingService tracking;

    @GetMapping
    public String view(Model model) {
        model.addAttribute("cart", cart);
        return "cart/view";
    }

    @PostMapping("/add")
    public String add(@RequestParam Long productId,
                      @RequestParam(defaultValue = "1") int quantity,
                      RedirectAttributes ra, HttpServletRequest request) {
        Product p = productService.findById(productId);
        int wanted = cart.getQuantityOf(productId) + quantity;
        if (quantity < 1 || wanted > p.getQuantity()) {
            ra.addFlashAttribute("error", "Không đủ hàng: \"" + p.getName() + "\" chỉ còn " + p.getQuantity());
            return "redirect:/products/" + productId;
        }
        var capture = tracking.capture(request);
        cart.add(p, quantity);
        tracking.added(capture, productId, quantity);
        ra.addFlashAttribute("message", "Đã thêm \"" + p.getName() + "\" vào giỏ hàng");
        return "redirect:/cart";
    }

    @PostMapping("/update")
    public String update(@RequestParam Long productId, @RequestParam int quantity, RedirectAttributes ra) {
        Product p = productService.findById(productId);
        if (quantity > p.getQuantity()) {
            ra.addFlashAttribute("error", "\"" + p.getName() + "\" chỉ còn " + p.getQuantity());
        } else {
            cart.update(productId, quantity);
        }
        return "redirect:/cart";
    }

    @PostMapping("/remove/{productId}")
    public String remove(@PathVariable Long productId) {
        cart.remove(productId);
        return "redirect:/cart";
    }

    @PostMapping("/clear")
    public String clear() {
        cart.clear();
        return "redirect:/cart";
    }
}

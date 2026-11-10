package com.example.eshop.controller;

import com.example.eshop.cart.Cart;
import com.example.eshop.dto.CheckoutForm;
import com.example.eshop.entity.Order;
import com.example.eshop.entity.PaymentMethod;
import com.example.eshop.entity.User;
import com.example.eshop.service.OrderService;
import com.example.eshop.service.UserService;
import com.example.eshop.service.tracking.TrackingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
@RequestMapping("/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private final Cart cart;
    private final OrderService orderService;
    private final UserService userService;
    private final TrackingService tracking;

    @GetMapping
    public String form(Model model, Principal principal) {
        if (cart.isEmpty()) return "redirect:/cart";
        User u = userService.findByUsername(principal.getName());
        CheckoutForm f = new CheckoutForm();
        f.setCustomerName(u.getFullName());
        f.setPhone(u.getPhone());
        f.setAddress(u.getAddress());
        model.addAttribute("form", f);
        model.addAttribute("cart", cart);
        return "checkout/form";
    }

    @PostMapping
    public String submit(@Valid @ModelAttribute("form") CheckoutForm form, BindingResult result,
                         Model model, RedirectAttributes ra, Principal principal, HttpServletRequest request) {
        if (result.hasErrors()) {
            model.addAttribute("cart", cart);
            return "checkout/form";
        }
        try {
            var capture = tracking.capture(request);
            Order order = orderService.placeOrder(form, cart, principal.getName());
            tracking.placed(capture, order.getId());
            cart.clear();
            if (order.getPaymentMethod() == PaymentMethod.BANKING) {
                return "redirect:/orders/" + order.getId() + "/payment";
            }
            ra.addFlashAttribute("message", "Đặt hàng thành công! Mã đơn #" + order.getId());
            return "redirect:/orders/" + order.getId();
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/cart";
        }
    }
}

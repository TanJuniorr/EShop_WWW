package com.example.eshop.controller;

import com.example.eshop.entity.Order;
import com.example.eshop.entity.PaymentMethod;
import com.example.eshop.entity.OrderStatus;
import com.example.eshop.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/my")
    public String myOrders(Principal principal, Model model) {
        model.addAttribute("orders", orderService.findByUsername(principal.getName()));
        return "order/my-orders";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Principal principal, Model model) {
        model.addAttribute("order", getOwnedOrder(id, principal));
        return "order/detail";
    }

    @GetMapping("/{id}/payment")
    public String payment(@PathVariable Long id, Principal principal, Model model) {
        Order order = getOwnedOrder(id, principal);
        if (order.getPaymentMethod() != PaymentMethod.BANKING || order.getStatus() != OrderStatus.NEW) {
            return "redirect:/orders/" + id;
        }
        model.addAttribute("order", order);
        return "order/payment";
    }

    @PostMapping("/{id}/pay")
    public String pay(@PathVariable Long id, Principal principal, RedirectAttributes ra) {
        getOwnedOrder(id, principal);
        try {
            orderService.markPaid(id);
            ra.addFlashAttribute("message", "Thanh toán giả lập thành công đơn #" + id);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/orders/" + id;
    }

    /** Kiểm tra quyền sở hữu: CUSTOMER A không xem được đơn của B chỉ bằng cách đổi id trên URL. */
    private Order getOwnedOrder(Long id, Principal principal) {
        Order o = orderService.findById(id);
        if (o.getUser() == null || !o.getUser().getUsername().equals(principal.getName())) {
            throw new AccessDeniedException("Đơn hàng không thuộc về bạn");   // → trang /403
        }
        return o;
    }
}

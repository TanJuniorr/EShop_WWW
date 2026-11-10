package com.example.eshop.controller.admin;

import com.example.eshop.entity.OrderStatus;
import com.example.eshop.service.OrderService;
import com.example.eshop.tracking.TrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;
    private final TrackingService tracking;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("orders", orderService.findAll());
        return "admin/order/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("order", orderService.findById(id));
        model.addAttribute("statuses", OrderStatus.values());
        return "admin/order/detail";
    }

    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable Long id, @RequestParam OrderStatus status, RedirectAttributes ra) {
        try {
            if (orderService.updateStatus(id, status)) {
                tracking.completed(id);
            }
            ra.addFlashAttribute("message", "Đã cập nhật trạng thái đơn #" + id);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/orders/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        orderService.delete(id);
        ra.addFlashAttribute("message", "Đã xoá đơn #" + id);
        return "redirect:/admin/orders";
    }
}

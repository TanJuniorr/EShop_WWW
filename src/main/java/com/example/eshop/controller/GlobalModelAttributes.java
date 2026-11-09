package com.example.eshop.controller;

import com.example.eshop.cart.Cart;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "com.example.eshop.controller")
@RequiredArgsConstructor
public class GlobalModelAttributes {

    private final Cart cart;

    @ModelAttribute("cartCount")
    public int cartCount() {
        return cart.getTotalQuantity();
    }
}

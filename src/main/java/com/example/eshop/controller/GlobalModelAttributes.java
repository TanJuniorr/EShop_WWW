package com.example.eshop.controller;

import com.example.eshop.service.ai.AiChatService;
import com.example.eshop.cart.Cart;
import com.example.eshop.service.tracking.TrackingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "com.example.eshop.controller")
@RequiredArgsConstructor
public class GlobalModelAttributes {

    private final Cart cart;
    private final ObjectProvider<AiChatService> aiChat;
    private final TrackingService tracking;

    @ModelAttribute("trackingEnabled")
    public boolean trackingEnabled() {
        return tracking.enabled();
    }

    @ModelAttribute("trackingConsent")
    public TrackingService.Consent trackingConsent(HttpServletRequest request) {
        return tracking.consent(request);
    }

    @ModelAttribute("aiChatEnabled")
    public boolean aiChatEnabled() {
        return aiChat.getIfAvailable() != null;
    }

    @ModelAttribute("cartCount")
    public int cartCount() {
        return cart.getTotalQuantity();
    }
}

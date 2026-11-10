package com.example.eshop.controller.ai;

import com.example.eshop.service.ai.AiChatService;
import com.example.eshop.service.tracking.TrackingService;

import com.example.eshop.service.ai.AiChatService;
import com.example.eshop.entity.Product;
import com.example.eshop.service.tracking.TrackingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
public class AiChatController {
    private final ObjectProvider<AiChatService> service;
    private final TrackingService tracking;

    AiChatController(ObjectProvider<AiChatService> service, TrackingService tracking) {
        this.service = service;
        this.tracking = tracking;
    }

    @PostMapping(path = "/ai/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest body, HttpServletRequest request) {
        var chat = service.getIfAvailable();
        if (chat == null) {
            throw AiChatService.unavailable();
        }
        var message = body.message();
        String text = message != null && message.isTextual() ? message.asString() : null;
        var capture = StringUtils.hasText(text) && text.length() <= AiChatService.MAX_MESSAGE_LENGTH
                ? tracking.capture(request) : null;
        AiChatService.Reply result;
        try {
            result = chat.chat(request.getSession(), request.getRemoteUser(), body.conversationId(), text);
        } catch (RuntimeException failure) {
            if (capture != null) {
                tracking.chat(capture, List.of());
            }
            throw failure;
        }
        var recommendations = result.products().stream().map(p -> Recommendation.from(p, request.getContextPath())).toList();
        UUID trackingId = capture == null ? null : tracking.chat(capture, recommendations.stream().map(Recommendation::id).toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ChatResponse(result.conversationId(), result.reply(), recommendations, trackingId));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ChatError> failure(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(new ChatError(failure.getReason()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ChatError> invalidRequest() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(new ChatError("Dữ liệu trò chuyện không hợp lệ."));
    }

    record ChatRequest(UUID conversationId, JsonNode message) {}
    record ChatResponse(UUID conversationId, String reply, List<Recommendation> products, UUID trackingId) {}
    record ChatError(String error) {}

    record Recommendation(Long id, String name, BigDecimal price, String imageUrl, String productUrl, boolean inStock) {
        static Recommendation from(Product p, String contextPath) {
            return new Recommendation(p.getId(), p.getName(), p.getPrice(), p.getImageUrl(),
                    contextPath + "/products/" + p.getId(), p.getQuantity() > 0);
        }
    }
}

package com.example.eshop.tracking;

import com.example.eshop.service.ai.ProductSemanticSearchService.Filters;
import com.example.eshop.service.ai.ProductSemanticSearchService.SearchMode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

public record TrackingEvent(UUID id, TrackingEventType type, Long productId, Long orderId,
                     UUID interactionId, Integer position, Integer quantity, String page,
                     SearchStats search, LocalDateTime at) {

    public static TrackingEvent page(TrackingEventType type, Long productId, String path, LocalDateTime at) {
        return new TrackingEvent(UUID.randomUUID(), type, productId, null, null, null, null, path, null, at);
    }

    public static TrackingEvent order(TrackingEventType type, Long orderId, LocalDateTime at) {
        var id = UUID.nameUUIDFromBytes((type + ":" + orderId).getBytes(StandardCharsets.UTF_8));
        return new TrackingEvent(id, type, null, orderId, null, null, null, null, null, at);
    }

    public TrackingEvent clickSource(TrackingEventType sourceType) {
        var sourceId = UUID.nameUUIDFromBytes((sourceType + ":" + id).getBytes(StandardCharsets.UTF_8));
        return new TrackingEvent(sourceId, sourceType, productId, null, interactionId, position, null, null, null, at);
    }

    public record SearchStats(String keyword, Integer count, int durationMs, String status, SearchMode mode, Filters filters) {}
}

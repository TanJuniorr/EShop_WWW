package com.example.eshop.tracking;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/tracking")
class TrackingController {
    private static final int MAX_BODY_BYTES = 1024;
    private final TrackingService tracking;
    private final JsonMapper json;

    TrackingController(TrackingService tracking, JsonMapper json) {
        this.tracking = tracking;
        this.json = json;
    }

    @GetMapping("/consent")
    ResponseEntity<ConsentResponse> consent(HttpServletRequest request) {
        return consentResponse(tracking.consent(request));
    }

    @PostMapping(path = "/consent", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ConsentResponse> choose(HttpServletRequest request) {
        var body = read(request, ConsentRequest.class);
        if (body.allowed() == null || !body.allowed().isBoolean()) {
            throw TrackingService.invalidEvent();
        }
        return consentResponse(tracking.choose(request, body.allowed().asBoolean()));
    }

    @PostMapping(path = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> events(HttpServletRequest request) {
        var body = read(request, EventRequest.class);
        if (body.eventId() == null || body.eventType() == null) {
            throw TrackingService.invalidEvent();
        }
        tracking.client(request, body.eventId(), body.eventType(), body.interactionId(), body.productId());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    private <T> T read(HttpServletRequest request, Class<T> type) {
        try {
            byte[] payload = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (payload.length > MAX_BODY_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Yêu cầu thống kê quá dài.");
            }
            T body = json.readValue(payload, type);
            if (body != null) {
                return body;
            }
        } catch (IOException | JacksonException malformed) {
            throw TrackingService.invalidEvent();
        }
        throw TrackingService.invalidEvent();
    }

    private static ResponseEntity<ConsentResponse> consentResponse(TrackingService.Consent consent) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ConsentResponse(consent));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> failure(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("error", failure.getReason()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "Chưa cập nhật được tùy chọn thống kê. Bạn thử lại sau."));
    }

    record ConsentRequest(JsonNode allowed) {}
    record ConsentResponse(TrackingService.Consent consent) {}
    record EventRequest(UUID eventId, TrackingEventType eventType, UUID interactionId, Long productId) {}
}

package com.example.eshop.ai;

import com.google.genai.errors.ApiException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("ai-dev")
public class ProductEmbeddingService {
    static final int MAX_TEXT_LENGTH = 1_000;

    private final ObjectProvider<EmbeddingModel> models;

    public ProductEmbeddingService(ObjectProvider<EmbeddingModel> models) {
        this.models = models;
    }

    public float[] embed(String text) {
        if (!StringUtils.hasText(text) || text.length() > MAX_TEXT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nhập văn bản từ 1 đến 1000 ký tự.");
        }

        EmbeddingModel model;
        float[] vector;
        try {
            model = models.getIfAvailable();
            vector = model == null ? null : model.embed(text.strip());
        } catch (RuntimeException failure) {
            throw providerFailure(failure);
        }
        if (model == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình GEMINI_API_KEY.");
        }
        if (vector == null || vector.length != AiDevConfiguration.DIMENSIONS) {
            throw invalidVector();
        }
        boolean nonZero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw invalidVector();
            }
            nonZero |= value != 0;
        }
        if (!nonZero) {
            throw invalidVector();
        }
        return vector;
    }

    private static ResponseStatusException invalidVector() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Gemini không trả về vector hữu hạn, khác 0 và đúng số chiều cấu hình.");
    }

    private static ResponseStatusException providerFailure(RuntimeException failure) {
        // SDK errors can include request details. Expose only the upstream status code.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiException apiError) {
                int status = apiError.code();
                return new ProviderFailure("Gemini từ chối yêu cầu embedding (HTTP " + status + ").",
                        status == 429 || status == 408 || status >= 500);
            }
        }
        return new ProviderFailure("Không thể gọi Gemini Embedding API. Kiểm tra kết nối và cấu hình Gemini.", true);
    }

    static final class ProviderFailure extends ResponseStatusException {
        private final boolean retryable;

        ProviderFailure(String reason, boolean retryable) {
            super(HttpStatus.BAD_GATEWAY, reason);
            this.retryable = retryable;
        }

        boolean retryable() {
            return retryable;
        }
    }
}

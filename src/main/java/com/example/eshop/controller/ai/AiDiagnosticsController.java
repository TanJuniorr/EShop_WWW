package com.example.eshop.controller.ai;

import com.example.eshop.config.ai.AiDevConfiguration;
import com.example.eshop.service.ai.ProductEmbeddingService;

import io.qdrant.client.QdrantClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

@RestController
@Profile("ai-dev")
@RequestMapping("/api/dev/ai")
@PreAuthorize("hasRole('SELLER')")
public class AiDiagnosticsController {
    private final ProductEmbeddingService embeddings;
    private final ObjectProvider<QdrantClient> qdrant;

    AiDiagnosticsController(ProductEmbeddingService embeddings, ObjectProvider<QdrantClient> qdrant) {
        this.embeddings = embeddings;
        this.qdrant = qdrant;
    }

    @PostMapping("/embedding")
    public EmbeddingCheck embedding(@Valid @RequestBody EmbeddingInput input) {
        return new EmbeddingCheck(AiDevConfiguration.MODEL, embeddings.embed(input.text()).length);
    }

    @GetMapping("/qdrant")
    public QdrantCheck qdrant() {
        try {
            var reply = qdrant.getObject().healthCheckAsync(Duration.ofSeconds(3)).get();
            return new QdrantCheck("UP", reply.getVersion());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw qdrantUnavailable();
        } catch (ExecutionException | RuntimeException e) {
            throw qdrantUnavailable();
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> diagnosticFailure(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(failure.getBody());
    }

    private static ResponseStatusException qdrantUnavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Không kiểm tra được Qdrant qua gRPC. Kiểm tra dịch vụ và cổng gRPC đã cấu hình.");
    }

    public record EmbeddingInput(@NotBlank @Size(max = ProductEmbeddingService.MAX_TEXT_LENGTH) String text) {}

    public record EmbeddingCheck(String model, int dimensions) {}

    public record QdrantCheck(String status, String version) {}
}

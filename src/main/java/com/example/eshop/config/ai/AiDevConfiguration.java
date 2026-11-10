package com.example.eshop.config.ai;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import static org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModelName.GEMINI_EMBEDDING_001;

@Configuration(proxyBeanMethods = false)
@Profile("ai-dev")
public class AiDevConfiguration {
    public static final String MODEL = GEMINI_EMBEDDING_001.getName();
    public static final int DIMENSIONS = 768;

    private static final String GEMINI_KEY_PROPERTY = "spring.ai.google.genai.embedding.api-key";

    @Bean(destroyMethod = "close")
    @Lazy
    @Conditional(GeminiKeyPresent.class)
    Client geminiClient(@Value("${" + GEMINI_KEY_PROPERTY + "}") String apiKey,
                        @Value("${eshop.ai.embedding.timeout-ms:10000}") int timeoutMs) {
        return Client.builder().apiKey(apiKey.strip()).vertexAI(false)
                .httpOptions(HttpOptions.builder().timeout(Math.max(500, Math.min(timeoutMs, 30_000)))
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build())
                .build();
    }

    @Bean
    @Lazy
    @Conditional(GeminiKeyPresent.class)
    EmbeddingModel geminiEmbeddingModel(Client geminiClient) {
        var connection = GoogleGenAiEmbeddingConnectionDetails.builder().genAiClient(geminiClient).build();
        var options = GoogleGenAiTextEmbeddingOptions.builder().model(MODEL).dimensions(DIMENSIONS).build();
        return new GoogleGenAiTextEmbeddingModel(connection, options, new RetryTemplate(RetryPolicy.withMaxRetries(0)));
    }

    @Bean
    @Lazy
    @Conditional(GeminiKeyPresent.class)
    ChatModel geminiShoppingChatModel(Client geminiClient, @Value("${eshop.ai.chat.model}") String model) {
        return GoogleGenAiChatModel.builder().genAiClient(geminiClient)
                .options(GoogleGenAiChatOptions.builder().model(model.strip()).maxOutputTokens(1024).build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .build();
    }

    @Bean(destroyMethod = "close")
    @Lazy
    QdrantClient qdrantClient(@Value("${spring.ai.vectorstore.qdrant.host}") String host,
                              @Value("${spring.ai.vectorstore.qdrant.port}") int port,
                              @Value("${spring.ai.vectorstore.qdrant.use-tls}") boolean tls) {
        var channel = OkHttpChannelBuilder.forAddress(host, port);
        if (!tls) {
            channel.usePlaintext();
        }
        // The explicit health check sets a deadline, unlike the SDK's check during build().
        return new QdrantClient(QdrantGrpcClient.newBuilder(channel.build(), true, false).build());
    }

    static final class GeminiKeyPresent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty(GEMINI_KEY_PROPERTY));
        }
    }
}

package com.example.eshop.infrastructure.ai;

import com.example.eshop.config.ai.AiDevConfiguration;

import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.GetPoints;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.UpdateResult;
import io.qdrant.client.grpc.Points.UpdateStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static io.qdrant.client.WithPayloadSelectorFactory.enable;
import static io.qdrant.client.WithPayloadSelectorFactory.include;
import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchValues;
import static io.qdrant.client.QueryFactory.nearest;

@Component
@Profile("ai-dev")
public class QdrantProductIndex {
    public static final String COLLECTION = "eshop_products_v1";
    public static final String SOURCE = "eshop-product-v1";
    private static final Duration DEADLINE = Duration.ofSeconds(5);
    private final ObjectProvider<QdrantClient> clients;

    public QdrantProductIndex(ObjectProvider<QdrantClient> clients) {
        this.clients = clients;
    }

    public synchronized boolean ensureCollection(boolean create) {
        QdrantClient client = clients.getObject();
        if (!await(client.collectionExistsAsync(COLLECTION, DEADLINE))) {
            if (!create) {
                return false;
            }
            try {
                await(client.createCollectionAsync(COLLECTION, VectorParams.newBuilder()
                        .setSize(AiDevConfiguration.DIMENSIONS).setDistance(Distance.Cosine).build(), DEADLINE));
            } catch (Failure e) {
                if (e.code != Status.Code.ALREADY_EXISTS) {
                    throw e;
                }
            }
        }
        var vectors = await(client.getCollectionInfoAsync(COLLECTION, DEADLINE))
                .getConfig().getParams().getVectorsConfig();
        if (!vectors.hasParams() || vectors.getParams().getSize() != AiDevConfiguration.DIMENSIONS
                || vectors.getParams().getDistance() != Distance.Cosine) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Collection eshop_products_v1 phải dùng vector không tên, 768 chiều và Cosine.");
        }
        return true;
    }

    public Optional<RetrievedPoint> find(PointId id) {
        return await(clients.getObject().retrieveAsync(GetPoints.newBuilder()
                .setCollectionName(COLLECTION).addIds(id).setWithPayload(enable(true)).build(), DEADLINE))
                .stream().findFirst();
    }

    public List<ScoredPoint> search(float[] vector, int limit, float threshold, Long categoryId, Duration deadline) {
        return search(vector, limit, threshold, categoryId, null, deadline);
    }

    public List<ScoredPoint> search(float[] vector, int limit, float threshold, Long categoryId,
                             List<Long> eligibleIds, Duration deadline) {
        var filter = Filter.newBuilder()
                .addMust(matchKeyword("source", SOURCE))
                .addMust(matchKeyword("embedding_model", AiDevConfiguration.MODEL));
        if (eligibleIds != null) {
            if (eligibleIds.isEmpty()) {
                return List.of();
            }
            // MariaDB already applied category, price and stock to these IDs. Indexed metadata may lag.
            filter.addMust(matchValues("product_id", eligibleIds));
        } else if (categoryId != null) {
            filter.addMust(match("category_id", categoryId));
        }
        return await(clients.getObject().queryAsync(QueryPoints.newBuilder()
                .setCollectionName(COLLECTION)
                .setQuery(nearest(vector))
                .setLimit(limit)
                .setScoreThreshold(threshold)
                .setFilter(filter)
                .setWithPayload(include(List.of("product_id")))
                .build(), deadline));
    }

    public void upsert(PointStruct point) {
        requireCompleted(await(clients.getObject().upsertAsync(COLLECTION, List.of(point), DEADLINE)));
    }

    public void delete(PointId id) {
        requireCompleted(await(clients.getObject().deleteAsync(COLLECTION, List.of(id), DEADLINE)));
    }

    private static void requireCompleted(UpdateResult result) {
        if (result.getStatus() != UpdateStatus.Completed) {
            throw new Failure(Status.Code.UNAVAILABLE);
        }
    }

    private static <T> T await(Future<T> call) {
        try {
            return call.get();
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new Failure(Status.Code.CANCELLED);
        } catch (ExecutionException e) {
            throw new Failure(Status.fromThrowable(e.getCause()).getCode());
        }
    }

    public static final class Failure extends ResponseStatusException {
        private final Status.Code code;

        public Failure(Status.Code code) {
            super(HttpStatus.SERVICE_UNAVAILABLE, "Lỗi Qdrant gRPC (" + code.name() + ").");
            this.code = code;
        }

        public boolean retryable() {
            return switch (code) {
                case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED, INTERNAL -> true;
                default -> false;
            };
        }
    }
}

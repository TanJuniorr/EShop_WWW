package com.example.eshop.tracking;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
class TrackingRepository {
    private final JdbcClient jdbc;

    TrackingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void start(UUID id, String owner, LocalDateTime at) {
        Long userId = owner == null ? null : jdbc.sql("select id from users where username = :owner")
                .param("owner", owner).query(Long.class).single();
        jdbc.sql("""
                insert into tracking_sessions (id, user_id, started_at, last_activity_at)
                values (:id, :user, :at, :at)
                """).param("id", id.toString()).param("user", userId).param("at", at).update();
    }

    void end(UUID id, LocalDateTime at, boolean revoke) {
        jdbc.sql("""
                update tracking_sessions set ended_at = coalesce(ended_at, :at),
                  revoked_at = case when :revoke then coalesce(revoked_at, :at) else revoked_at end
                where id = :id
                """).param("at", at).param("revoke", revoke).param("id", id.toString()).update();
    }

    @Transactional
    public void append(UUID sessionId, List<TrackingEvent> events) {
        String session = sessionId.toString();
        int active = jdbc.sql("""
                update tracking_sessions set last_activity_at =
                  case when last_activity_at < :at then :at else last_activity_at end
                where id = :id and ended_at is null and revoked_at is null
                """).param("at", events.get(0).at()).param("id", session).update();
        // A retry can use the same timestamp and some drivers report only changed rows.
        if (active == 0 && jdbc.sql("""
                select id from tracking_sessions where id = :id and ended_at is null and revoked_at is null for update
                """).param("id", session).query(String.class).optional().isEmpty()) {
            return;
        }
        if (jdbc.sql("select id from tracking_events where event_id = :id")
                .param("id", events.get(0).id().toString()).query(Long.class).optional().isPresent()) {
            return;
        }
        for (TrackingEvent event : events) {
            insert(session, event);
        }
    }

    @Transactional
    public void complete(TrackingEvent event) {
        var session = jdbc.sql("""
                select session_id from tracking_events where event_id = :placed
                """).param("placed", TrackingEvent.order(TrackingEventType.ORDER_PLACED, event.orderId(), event.at())
                .id().toString()).query(String.class).optional();
        if (session.isPresent() && jdbc.sql("""
                select id from tracking_sessions where id = :id and revoked_at is null for update
                """).param("id", session.get()).query(String.class).optional().isPresent()) {
            insert(session.get(), event);
        }
    }

    private void insert(String session, TrackingEvent event) {
        var search = event.search();
        var filters = search == null ? null : search.filters();
        jdbc.sql("""
                insert into tracking_events
                  (event_id, session_id, event_type, product_id, order_id, interaction_id, result_position,
                   search_keyword, search_result_count, duration_ms, search_status, search_mode, category_id,
                   min_price, max_price, min_inclusive, max_inclusive, in_stock, sort_label, quantity, page_path, created_at)
                values
                  (:id, :session, :type, :product, :orderId, :interaction, :position,
                   :keyword, :resultCount, :duration, :status, :mode, :category,
                   :minPrice, :maxPrice, :minInclusive, :maxInclusive, :stock, :sort, :quantity, :page, :at)
                on duplicate key update event_id = event_id
                """).param("id", event.id().toString()).param("session", session).param("type", event.type().name())
                .param("product", event.productId()).param("orderId", event.orderId())
                .param("interaction", event.interactionId() == null ? null : event.interactionId().toString())
                .param("position", event.position()).param("quantity", event.quantity()).param("page", event.page())
                .param("keyword", search == null ? null : search.keyword()).param("resultCount", search == null ? null : search.count())
                .param("duration", search == null ? null : search.durationMs()).param("status", search == null ? null : search.status())
                .param("mode", search == null || search.mode() == null ? null : search.mode().name())
                .param("category", filters == null ? null : filters.categoryId()).param("minPrice", filters == null ? null : filters.min())
                .param("maxPrice", filters == null ? null : filters.max()).param("minInclusive", filters == null ? null : filters.minInclusive())
                .param("maxInclusive", filters == null ? null : filters.maxInclusive()).param("stock", filters == null ? null : filters.inStock())
                .param("sort", filters == null ? null : filters.orderLabel()).param("at", event.at()).update();
    }

    @Transactional
    public void purge(LocalDateTime now, LocalDateTime cutoff, LocalDateTime idle) {
        jdbc.sql("update tracking_sessions set ended_at = :now where ended_at is null and last_activity_at < :idle")
                .param("now", now).param("idle", idle).update();
        jdbc.sql("delete from tracking_events where created_at < :cutoff").param("cutoff", cutoff).update();
        jdbc.sql("""
                delete from tracking_sessions where last_activity_at < :cutoff
                  and not exists (select 1 from tracking_events e where e.session_id = tracking_sessions.id)
                """).param("cutoff", cutoff).update();
    }
}

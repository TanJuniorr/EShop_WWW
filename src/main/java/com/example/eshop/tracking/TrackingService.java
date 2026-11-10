package com.example.eshop.tracking;

import com.example.eshop.ai.ProductSemanticSearchService.SearchResult;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.example.eshop.tracking.TrackingEventType.*;

@Service
public class TrackingService {
    private static final Logger LOG = LoggerFactory.getLogger(TrackingService.class);
    private static final String ATTRIBUTE = TrackingService.class.getName();
    private static final Duration IDLE = Duration.ofMinutes(30);
    private final TrackingRepository store;
    private final boolean enabled;
    private final int retentionDays;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512), runnable -> {
                var thread = new Thread(runnable, "eshop-tracking");
                thread.setDaemon(true);
                return thread;
            });

    TrackingService(TrackingRepository store, @Value("${eshop.tracking.enabled:false}") boolean enabled,
                    @Value("${eshop.tracking.retention-days:30}") int retentionDays) {
        this.store = store;
        this.enabled = enabled;
        this.retentionDays = Math.max(1, Math.min(365, retentionDays));
    }

    public boolean enabled() {
        return enabled;
    }

    public Consent consent(HttpServletRequest request) {
        var state = state(request, false);
        if (state == null) {
            return Consent.UNSET;
        }
        synchronized (state) {
            expire(state, now());
            return state.consent;
        }
    }

    Consent choose(HttpServletRequest request, boolean allow) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Thống kê đang tắt.");
        }
        var state = state(request, true);
        synchronized (state) {
            if (state.retired) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiên đã thay đổi.");
            }
            var time = now();
            expire(state, time);
            if (state.pendingRevoke != null) {
                store.end(state.pendingRevoke, time, true);
                state.pendingRevoke = null;
            }
            if (!allow) {
                state.consent = Consent.DECLINED;
                if (state.visit != null) {
                    state.visit.revoked = true;
                }
                state.visit = null;
                if (state.lastVisitId != null) {
                    state.pendingRevoke = state.lastVisitId;
                    state.lastVisitId = null;
                    store.end(state.pendingRevoke, time, true);
                    state.pendingRevoke = null;
                }
            } else if (state.visit == null) {
                if (state.consentWindow == null || !time.isBefore(state.consentWindow.plusMinutes(1))) {
                    state.consentWindow = time;
                    state.optIns = 0;
                }
                if (state.optIns++ >= 10) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Bạn thử lại tùy chọn sau một phút.");
                }
                var visit = new Visit(time);
                store.start(visit.id, state.owner, time);
                state.visit = visit;
                state.lastVisitId = visit.id;
                state.consent = Consent.ACCEPTED;
            }
            return state.consent;
        }
    }

    public Capture capture(HttpServletRequest request) {
        var state = state(request, false);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            expire(state, now());
            return state.visit == null ? null : new Capture(state, state.visit);
        }
    }

    void page(Capture capture, String path, Long productId) {
        var at = now();
        if (productId == null) {
            record(capture, List.of(TrackingEvent.page(PAGE_VIEW, null, path, at)));
        } else {
            record(capture, List.of(TrackingEvent.page(PAGE_VIEW, productId, path, at),
                    TrackingEvent.page(PRODUCT_VIEW, productId, path, at)));
        }
    }

    public UUID catalog(Capture capture, List<Long> ids) {
        return offer(capture, Source.CATALOG, UUID.randomUUID(), ids, null);
    }

    public UUID search(Capture capture, String query, SearchResult result, List<Long> ids, long started) {
        if (capture == null) {
            return null;
        }
        var id = UUID.randomUUID();
        var stats = new TrackingEvent.SearchStats(SearchKeyword.forAnalytics(query), result == null ? null : ids.size(),
                (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)),
                result == null ? "ERROR" : "OK", result == null ? null : result.mode(), result == null ? null : result.filters());
        var event = new TrackingEvent(id, AI_SEARCH, null, null, null, null, null, "/products", stats, now());
        return offer(capture, Source.AI_SEARCH, id, ids, event);
    }

    public UUID chat(Capture capture, List<Long> ids) {
        var id = UUID.randomUUID();
        var event = new TrackingEvent(id, CHATBOT_MESSAGE_SENT, null, null, null, null, null, null, null, now());
        return offer(capture, Source.CHATBOT, id, ids, event);
    }

    public void added(Capture capture, Long productId, int quantity) {
        if (capture != null) {
            record(capture, List.of(new TrackingEvent(UUID.randomUUID(), ADD_TO_CART, productId, null,
                    null, null, quantity, null, null, now())));
        }
    }

    public void placed(Capture capture, Long orderId) {
        if (capture != null) {
            record(capture, List.of(TrackingEvent.order(ORDER_PLACED, orderId, now())));
        }
    }

    public void completed(Long orderId) {
        if (enabled) {
            var event = TrackingEvent.order(ORDER_COMPLETED, orderId, now());
            submit(() -> store.complete(event));
        }
    }

    void client(HttpServletRequest request, UUID id, TrackingEventType type, UUID interaction, Long productId) {
        var capture = capture(request);
        if (capture == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Phiên chưa đồng ý thống kê.");
        }
        synchronized (capture.state) {
            if (!active(capture)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Phiên chưa đồng ý thống kê.");
            }
            var visit = capture.visit;
            var eventId = UUID.nameUUIDFromBytes(("ui:" + visit.id + ":" + id).getBytes(StandardCharsets.UTF_8));
            var at = now();
            if (Duration.between(visit.rateWindow, at).compareTo(Duration.ofMinutes(1)) >= 0) {
                visit.rateWindow = at;
                visit.clientCount = 0;
            }
            if (visit.clientCount++ >= 60) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Quá nhiều sự kiện.");
            }
            if (type == CHATBOT_OPEN && interaction == null && productId == null) {
                record(capture, List.of(new TrackingEvent(eventId, type, null, null, null, null, null, null, null, at)));
                return;
            }
            if (type != PRODUCT_CLICK || productId == null || productId <= 0 || interaction == null) {
                throw invalidEvent();
            }
            var offer = visit.offers.get(interaction);
            int position = offer == null ? -1 : offer.products.indexOf(productId);
            if (position < 0 || offer.at.plus(IDLE).isBefore(at)) {
                throw invalidEvent();
            }
            var click = new TrackingEvent(eventId, PRODUCT_CLICK, productId, null, interaction, position + 1, null, null, null, at);
            var events = new ArrayList<TrackingEvent>();
            events.add(click);
            if (offer.source == Source.AI_SEARCH) {
                events.add(click.clickSource(AI_SEARCH_RESULT_CLICK));
            } else if (offer.source == Source.CHATBOT) {
                events.add(click.clickSource(CHATBOT_PRODUCT_CLICK));
            }
            record(capture, events);
        }
    }

    private UUID offer(Capture capture, Source source, UUID id, List<Long> ids, TrackingEvent event) {
        if (capture == null) {
            return null;
        }
        synchronized (capture.state) {
            if (!active(capture)) {
                return null;
            }
            if (event != null && !record(capture, List.of(event))) {
                return null;
            }
            if (ids.isEmpty()) {
                return null;
            }
            var offers = capture.visit.offers;
            offers.put(id, new Offer(source, List.copyOf(ids), now()));
            if (offers.size() > 24) {
                offers.remove(offers.keySet().iterator().next());
            }
            return id;
        }
    }

    private boolean record(Capture capture, List<TrackingEvent> events) {
        if (capture == null) {
            return false;
        }
        synchronized (capture.state) {
            if (!active(capture)) {
                return false;
            }
            capture.visit.last = now();
            return submit(() -> {
                if (!capture.visit.revoked) {
                    store.append(capture.visit.id, events);
                }
            });
        }
    }

    private static boolean active(Capture capture) {
        return !capture.state.retired && capture.state.visit == capture.visit
                && now().isBefore(capture.visit.last.plus(IDLE));
    }

    private void expire(State state, LocalDateTime at) {
        if (state.visit != null && !at.isBefore(state.visit.last.plus(IDLE))) {
            close(state, at);
            state.consent = Consent.UNSET;
        }
    }

    private void close(State state, LocalDateTime at) {
        if (state.visit != null) {
            var id = state.visit.id;
            state.visit = null;
            submit(() -> store.end(id, at, false));
        }
    }

    public void logout(HttpServletRequest request) {
        var state = state(request, false);
        if (state != null) {
            synchronized (state) {
                state.retired = true;
                close(state, now());
            }
        }
    }

    private synchronized State state(HttpServletRequest request, boolean create) {
        if (!enabled) {
            return null;
        }
        HttpSession session = request.getSession(create);
        if (session == null) {
            return null;
        }
        try {
            String owner = request.getRemoteUser();
            if (session.getAttribute(ATTRIBUTE) instanceof State existing) {
                if (Objects.equals(existing.owner, owner)) {
                    return existing;
                }
                synchronized (existing) {
                    existing.retired = true;
                    close(existing, now());
                }
                session.removeAttribute(ATTRIBUTE);
            }
            if (create) {
                var state = new State(owner);
                session.setAttribute(ATTRIBUTE, state);
                return state;
            }
        } catch (IllegalStateException invalidated) {
            // A concurrent logout invalidated the HTTP session.
            if (create) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiên đã thay đổi.");
            }
        }
        return null;
    }

    private boolean submit(Runnable action) {
        try {
            writer.execute(() -> {
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    LOG.debug("Analytics write could not be completed");
                }
            });
            return true;
        } catch (RejectedExecutionException busy) {
            return false;
        }
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
    void purge() {
        var time = now();
        submit(() -> store.purge(time, time.minusDays(retentionDays), time.minus(IDLE)));
    }

    void awaitWrites() throws Exception {
        writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    static ResponseStatusException invalidEvent() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sự kiện thống kê không hợp lệ.");
    }

    public enum Consent { UNSET, ACCEPTED, DECLINED }
    private enum Source { CATALOG, AI_SEARCH, CHATBOT }
    private record Offer(Source source, List<Long> products, LocalDateTime at) {}

    public static final class Capture {
        private final State state;
        private final Visit visit;

        private Capture(State state, Visit visit) {
            this.state = state;
            this.visit = visit;
        }
    }

    private static final class State {
        private final String owner;
        private Consent consent = Consent.UNSET;
        private boolean retired;
        private Visit visit;
        private UUID lastVisitId;
        private UUID pendingRevoke;
        private LocalDateTime consentWindow;
        private int optIns;

        private State(String owner) {
            this.owner = owner;
        }
    }

    private static final class Visit {
        private final UUID id = UUID.randomUUID();
        private final LinkedHashMap<UUID, Offer> offers = new LinkedHashMap<>();
        private LocalDateTime last;
        private LocalDateTime rateWindow;
        private int clientCount;
        private volatile boolean revoked;

        private Visit(LocalDateTime time) {
            last = rateWindow = time;
        }
    }
}

package com.example.eshop.service.tracking;

import com.example.eshop.tracking.TrackingEventType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.eshop.tracking.TrackingEventType.*;

@Service
public class AnalyticsService {
    private static final int PAGE_SIZE = 10;
    private static final String TIME = "e.created_at >= :from and e.created_at < :until";
    private static final String PRODUCT_EVENTS = """
            'PRODUCT_VIEW', 'PRODUCT_CLICK', 'ADD_TO_CART', 'AI_SEARCH_RESULT_CLICK', 'CHATBOT_PRODUCT_CLICK'
            """;
    private final JdbcClient jdbc;
    private final ZoneId zone;

    public AnalyticsService(JdbcClient jdbc, @Value("${eshop.tracking.zone:Asia/Ho_Chi_Minh}") String zone) {
        this.jdbc = jdbc;
        this.zone = ZoneId.of(zone);
    }

    @Transactional(readOnly = true)
    public Report report(String period, String start, String end, String sort, int page) {
        var range = range(period, start, end);
        Sort order;
        try {
            order = Sort.valueOf(sort);
        } catch (IllegalArgumentException invalidSort) {
            throw invalid("Tiêu chí sắp xếp không hợp lệ.");
        }
        if (page < 0 || page > 1000) {
            throw invalid("Số trang không hợp lệ.");
        }

        var counts = new LinkedHashMap<String, Long>();
        var byType = query("""
                select e.event_type, count(*) as total from tracking_events e
                where %s group by e.event_type
                """.formatted(TIME), range).query((rs, row) -> Map.entry(rs.getString("event_type"), rs.getLong("total"))).list();
        for (var type : TrackingEventType.values()) {
            counts.put(type.name(), 0L);
        }
        byType.forEach(entry -> counts.put(entry.getKey(), entry.getValue()));

        Visitors visitors = query("""
                select count(distinct e.session_id) as sessions, count(distinct s.user_id) as accounts,
                    count(distinct case when s.user_id is null then e.session_id end) as guests,
                    count(distinct case when e.event_type = 'ORDER_PLACED' then e.session_id end) as ordering
                from tracking_events e join tracking_sessions s on s.id = e.session_id
                where %s
                """.formatted(TIME), range).query((rs, row) -> new Visitors(rs.getLong("sessions"), rs.getLong("accounts"),
                rs.getLong("guests"), rs.getLong("ordering"))).single();

        var funnel = funnel(range);
        var items = products(range, order, PAGE_SIZE + 1, (long) page * PAGE_SIZE, null);
        var summary = new Summary(counts, visitors, percent(visitors.orderingSessions(), visitors.sessions()));
        var search = searchStats(range);
        var chat = chatStats(range);
        return new Report(range.start(), range.end(), zone.getId(), summary, search, chat, funnel, daily(range),
                items.stream().limit(PAGE_SIZE).toList(), page, items.size() > PAGE_SIZE,
                keywords(range), products(range, Sort.SEARCH, 5, 0, AI_SEARCH_RESULT_CLICK),
                products(range, Sort.CHAT, 5, 0, CHATBOT_PRODUCT_CLICK));
    }

    private SearchStats searchStats(Range range) {
        var base = query("""
                select count(distinct e.session_id) as sessions,
                    count(case when e.search_status = 'OK' then 1 end) as successful,
                    count(case when e.search_status = 'OK' and e.search_result_count = 0 then 1 end) as empty_results,
                    count(case when e.search_status = 'OK' and e.search_result_count > 0 then 1 end) as with_results,
                    count(case when e.search_status = 'ERROR' then 1 end) as errors,
                    avg(e.duration_ms) as duration
                from tracking_events e where e.event_type = 'AI_SEARCH' and %s
                """.formatted(TIME), range).query((rs, row) -> new SearchBase(rs.getLong("sessions"),
                rs.getLong("successful"), rs.getLong("empty_results"), rs.getLong("with_results"),
                rs.getLong("errors"), rs.getObject("duration", Double.class))).single();
        long clicked = query("""
                select count(distinct e.event_id)
                from tracking_events e join tracking_events c on c.interaction_id = e.event_id
                  and c.session_id = e.session_id and c.event_type = 'AI_SEARCH_RESULT_CLICK'
                  and c.created_at >= :from and c.created_at < :until
                where e.event_type = 'AI_SEARCH' and e.search_result_count > 0 and %s
                """.formatted(TIME), range).query(Long.class).single();
        return new SearchStats(base.sessions(), base.empty(), base.errors(), base.duration(), clicked,
                percent(base.empty(), base.successful()), percent(clicked, base.withResults()));
    }

    private ChatStats chatStats(Range range) {
        long sessions = query("""
                select count(distinct e.session_id) from tracking_events e
                where e.event_type in ('CHATBOT_OPEN', 'CHATBOT_MESSAGE_SENT', 'CHATBOT_PRODUCT_CLICK') and %s
                """.formatted(TIME), range).query(Long.class).single();
        return query("""
                select count(distinct e.session_id) as opened,
                    count(distinct case when c.id is not null then e.session_id end) as engaged
                from tracking_events e left join tracking_events c on c.session_id = e.session_id
                  and c.event_type in ('CHATBOT_MESSAGE_SENT', 'CHATBOT_PRODUCT_CLICK')
                  and c.id > e.id and c.created_at >= :from and c.created_at < :until
                where e.event_type = 'CHATBOT_OPEN' and %s
                """.formatted(TIME), range).query((rs, row) -> new ChatStats(sessions, rs.getLong("opened"),
                rs.getLong("engaged"), percent(rs.getLong("engaged"), rs.getLong("opened")))).single();
    }

    private List<Keyword> keywords(Range range) {
        return query("""
                select e.search_keyword, count(*) as total from tracking_events e
                where e.event_type = 'AI_SEARCH' and e.search_keyword is not null and %s
                group by e.search_keyword order by total desc, e.search_keyword asc limit 10
                """.formatted(TIME), range).query((rs, row) -> new Keyword(rs.getString("search_keyword"), rs.getLong("total"))).list();
    }

    private List<ProductStats> products(Range range, Sort sort, int limit, long offset, TrackingEventType source) {
        return query("""
                select e.product_id, p.name as product_name, c.name as category_name,
                    count(case when e.event_type = 'PRODUCT_VIEW' then 1 end) as views,
                    count(case when e.event_type = 'PRODUCT_CLICK' then 1 end) as clicks,
                    count(case when e.event_type = 'ADD_TO_CART' then 1 end) as carts,
                    count(case when e.event_type = 'AI_SEARCH_RESULT_CLICK' then 1 end) as search_clicks,
                    count(case when e.event_type = 'CHATBOT_PRODUCT_CLICK' then 1 end) as chat_clicks
                from tracking_events e left join product p on p.id = e.product_id
                left join category c on c.id = p.category_id
                where e.product_id is not null and e.event_type in (%s) and %s
                group by e.product_id, p.name, c.name
                %s
                order by %s desc, e.product_id asc limit :limit offset :offset
                """.formatted(PRODUCT_EVENTS, TIME, source == null ? "" :
                "having count(case when e.event_type = '" + source.name() + "' then 1 end) > 0", sort.column), range)
                .param("limit", limit).param("offset", offset)
                .query((rs, row) -> new ProductStats(rs.getLong("product_id"), rs.getString("product_name"),
                        rs.getString("category_name"), rs.getLong("views"), rs.getLong("clicks"),
                        rs.getLong("carts"), rs.getLong("search_clicks"), rs.getLong("chat_clicks"))).list();
    }

    private List<Day> daily(Range range) {
        int length = (int) ChronoUnit.DAYS.between(range.start(), range.end()) + 1;
        var sql = new StringBuilder("select case ");
        for (int day = 0; day < length; day++) {
            sql.append("when e.created_at < :day").append(day).append(" then ").append(day).append(' ');
        }
        sql.append("""
                else -1 end as bucket, e.event_type, count(*) as total from tracking_events e
                where e.event_type in ('PAGE_VIEW', 'AI_SEARCH', 'AI_SEARCH_RESULT_CLICK') and
                """).append(TIME).append(" group by bucket, e.event_type");
        var query = query(sql.toString(), range);
        for (int day = 0; day < length; day++) {
            query.param("day" + day, utc(range.start().plusDays(day + 1)));
        }
        long[][] daily = new long[length][3];
        query.query((RowCallbackHandler) rs -> {
            int metric = switch (TrackingEventType.valueOf(rs.getString("event_type"))) {
                case PAGE_VIEW -> 0;
                case AI_SEARCH -> 1;
                default -> 2;
            };
            daily[rs.getInt("bucket")][metric] = rs.getLong("total");
        });
        var days = new ArrayList<Day>(length);
        for (int day = 0; day < length; day++) {
            days.add(new Day(range.start().plusDays(day), daily[day][0], daily[day][1], daily[day][2]));
        }
        return days;
    }

    private Funnel funnel(Range range) {
        return query("""
                with scoped as (
                    select e.id, e.session_id, e.order_id, e.event_type from tracking_events e
                    where e.event_type in ('PRODUCT_VIEW', 'ADD_TO_CART', 'ORDER_PLACED', 'ORDER_COMPLETED') and %s
                ), viewed as (
                    select session_id, min(id) as first_id from scoped where event_type = 'PRODUCT_VIEW' group by session_id
                ), carted as (
                    select s.session_id, min(s.id) as first_id from scoped s join viewed v on v.session_id = s.session_id
                    where s.event_type = 'ADD_TO_CART' and s.id > v.first_id group by s.session_id
                ), placed as (
                    select s.id, s.session_id, s.order_id from scoped s join carted c on c.session_id = s.session_id
                    where s.event_type = 'ORDER_PLACED' and s.id > c.first_id
                ), completed as (
                    select distinct s.session_id from scoped s join placed p on p.session_id = s.session_id
                      and p.order_id = s.order_id and s.id > p.id where s.event_type = 'ORDER_COMPLETED'
                )
                select (select count(*) from viewed) as viewed, (select count(*) from carted) as carted,
                    (select count(distinct session_id) from placed) as placed, (select count(*) from completed) as completed
                """.formatted(TIME), range).query((rs, row) -> new Funnel(rs.getLong("viewed"), rs.getLong("carted"),
                rs.getLong("placed"), rs.getLong("completed"))).single();
    }

    private JdbcClient.StatementSpec query(String sql, Range range) {
        return jdbc.sql(sql).param("from", range.from()).param("until", range.until());
    }

    private Range range(String period, String start, String end) {
        LocalDate today = LocalDate.now(zone);
        LocalDate from;
        LocalDate to = today;
        switch (period) {
            case "today" -> from = today;
            case "7" -> from = today.minusDays(6);
            case "30" -> from = today.minusDays(29);
            case "custom" -> {
                if (start == null || end == null) {
                    throw invalid("Chọn đủ ngày bắt đầu và ngày kết thúc.");
                }
                try {
                    from = LocalDate.parse(start);
                    to = LocalDate.parse(end);
                } catch (DateTimeParseException malformed) {
                    throw invalid("Ngày lọc không hợp lệ.");
                }
            }
            default -> throw invalid("Khoảng thời gian không hợp lệ.");
        }
        if (from.getYear() < 2000 || to.isAfter(today) || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 93) {
            throw invalid("Chọn khoảng tối đa 93 ngày, kết thúc không muộn hơn hôm nay.");
        }
        return new Range(from, to, utc(from), utc(to.plusDays(1)));
    }

    private LocalDateTime utc(LocalDate day) {
        return LocalDateTime.ofInstant(day.atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static Double percent(long numerator, long denominator) {
        return denominator == 0 ? null : 100.0 * numerator / denominator;
    }

    private enum Sort {
        VIEWS("views"), CLICKS("clicks"), CARTS("carts"), SEARCH("search_clicks"), CHAT("chat_clicks");
        private final String column;
        Sort(String column) { this.column = column; }
    }

    private record Range(LocalDate start, LocalDate end, LocalDateTime from, LocalDateTime until) {}
    private record SearchBase(long sessions, long successful, long empty, long withResults, long errors, Double duration) {}
    public record Visitors(long sessions, long accounts, long anonymousSessions, long orderingSessions) {}
    public record Summary(Map<String, Long> counts, Visitors visitors, Double conversionRate) {}
    public record SearchStats(long sessions, long emptyResults, long errors, Double averageMs,
                              long clickedSearches, Double emptyRate, Double clickRate) {}
    public record ChatStats(long sessions, long openedSessions, long engagedSessions, Double engagementRate) {}
    public record Keyword(String keyword, long count) {}
    public record Day(LocalDate date, long visits, long searches, long searchClicks) {}
    public record ProductStats(long id, String name, String category, long views, long clicks, long carts,
                               long searchClicks, long chatClicks) {}
    public record Funnel(long viewed, long carted, long placed, long completed) {}
    public record Report(LocalDate start, LocalDate end, String zone, Summary summary, SearchStats search, ChatStats chat,
                         Funnel funnel, List<Day> daily, List<ProductStats> products, int page, boolean moreProducts,
                         List<Keyword> keywords, List<ProductStats> searchProducts, List<ProductStats> chatProducts) {}
}

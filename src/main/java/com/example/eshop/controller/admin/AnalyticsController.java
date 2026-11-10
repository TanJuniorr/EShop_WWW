package com.example.eshop.controller.admin;

import com.example.eshop.tracking.AnalyticsService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Controller
@RequestMapping("/admin/analytics")
class AnalyticsController {
    private final AnalyticsService analytics;

    AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping
    String dashboard() {
        return "admin/analytics";
    }

    @GetMapping("/data")
    ResponseEntity<AnalyticsService.Report> data(@RequestParam(defaultValue = "7") String period,
                                                 @RequestParam(required = false) String start,
                                                 @RequestParam(required = false) String end,
                                                 @RequestParam(defaultValue = "VIEWS") String sort,
                                                 @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(analytics.report(period, start, end, sort, page));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> invalid(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("error", failure.getReason()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "Chưa đọc được dữ liệu thống kê. Kiểm tra kết nối hoặc migration rồi thử lại."));
    }
}

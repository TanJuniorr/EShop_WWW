package com.example.eshop.config.tracking;

import com.example.eshop.service.tracking.TrackingService;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class TrackingWebConfiguration implements WebMvcConfigurer {
    private static final String CAPTURE = TrackingWebConfiguration.class.getName();
    private final TrackingService tracking;

    TrackingWebConfiguration(TrackingService tracking) {
        this.tracking = tracking;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("GET".equals(request.getMethod()) && request.getDispatcherType() == DispatcherType.REQUEST) {
                    request.setAttribute(CAPTURE, tracking.capture(request));
                }
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
                if (failure != null || response.getStatus() != 200
                        || !(request.getAttribute(CAPTURE) instanceof TrackingService.Capture capture)) {
                    return;
                }
                String path = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                if ("/products/{id}".equals(path)
                        && request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables
                        && variables.get("id") instanceof String id) {
                    tracking.page(capture, "/products/" + Long.parseLong(id), Long.valueOf(id));
                } else if (path != null) {
                    tracking.page(capture, path, null);
                }
            }
        }).addPathPatterns("/", "/products", "/products/{id}", "/login", "/register");
    }
}

package com.example.eshop.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Đọc JWT từ header "Authorization: Bearer ..." (API) hoặc cookie ACCESS_TOKEN (web),
 * hợp lệ thì đặt Authentication vào SecurityContext.
 * KHÔNG đánh dấu @Component: nếu là bean, Spring Boot sẽ đăng ký nó thêm một lần nữa
 * làm servlet filter toàn cục → chạy 2 lần cho mỗi request.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                String username = jwtService.extractUsername(token);
                // Nạp lại từ DB để tài khoản bị khoá / đổi quyền có hiệu lực ngay
                UserDetails user = userDetailsService.loadUserByUsername(username);
                if (user.isEnabled()) {
                    var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(auth);
                    SecurityContextHolder.setContext(context);
                }
            } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
                // Token sai/hết hạn: bỏ qua, request tiếp tục như khách (GUEST)
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        // /api/** chỉ dùng Bearer header như tài liệu, không nhận cookie web.
        // Tránh một request API thay đổi dữ liệu dùng nhầm quyền từ cookie trình duyệt.
        if (request.getServletPath().startsWith("/api/")) {
            return null;
        }
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (JwtService.COOKIE_NAME.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }
}

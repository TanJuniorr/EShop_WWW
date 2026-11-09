package com.example.eshop.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.stream.Collectors;

@Service
public class JwtService {

    public static final String COOKIE_NAME = "ACCESS_TOKEN";

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-ms}") long expirationMs) {
        // Đọc khóa từ môi trường; với demo local có thể tạo khóa phiên chạy nếu chưa khai báo.
        // Khóa tạm tránh việc nhúng bí mật dùng chung vào repository/Git.
        this.key = StringUtils.hasText(secret)
                ? Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))
                : Jwts.SIG.HS256.key().build();
        this.expirationMs = expirationMs;
    }

    public String generateToken(UserDetails user) {
        String roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
        Date now = new Date();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)                // HS256 vì key 256+ bit
                .compact();
    }

    /** Ném JwtException nếu token sai chữ ký, sai định dạng hoặc đã hết hạn. */
    public String extractUsername(String token) throws JwtException {
        return parseClaims(token).getSubject();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationSeconds() {
        return expirationMs / 1000;
    }
}

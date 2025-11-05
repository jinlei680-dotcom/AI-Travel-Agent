package com.aitravel.planner.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {
    private final SecretKey key;
    private final long expiresMinutes;

    public JwtService(
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.expiresMinutes}") long expiresMinutes
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.expiresMinutes = expiresMinutes;
    }

    public String issueToken(UUID userId, String email, Map<String, Object> claims) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expiresMinutes * 60);
        return Jwts.builder()
                .setSubject(userId.toString())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .addClaims(claims)
                .claim("email", email)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }
}
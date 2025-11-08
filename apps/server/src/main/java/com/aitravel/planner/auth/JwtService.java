package com.aitravel.planner.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

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

    public Optional<ParsedToken> parse(String token) {
        try {
            Claims c = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
            String sub = c.getSubject();
            String email = c.get("email", String.class);
            UUID userId = UUID.fromString(sub);
            return Optional.of(new ParsedToken(userId, email));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public record ParsedToken(UUID userId, String email) {}
}

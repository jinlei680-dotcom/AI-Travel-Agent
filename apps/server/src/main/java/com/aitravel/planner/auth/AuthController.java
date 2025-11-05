package com.aitravel.planner.auth;

import com.aitravel.planner.model.User;
import com.aitravel.planner.repo.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "*", allowCredentials = "false")
public class AuthController {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        if (req.email() == null || req.password() == null || req.email().isBlank() || req.password().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "邮箱和密码不能为空"));
        }
        if (users.existsByEmail(req.email())) {
            return ResponseEntity.status(409).body(Map.of("error", "邮箱已被注册"));
        }
        User u = new User();
        u.setEmail(req.email());
        u.setPasswordHash(encoder.encode(req.password()));
        users.save(u);
        String token = jwt.issueToken(u.getId(), u.getEmail(), Map.of("type", "access"));
        return ResponseEntity.ok(new AuthResponse(token));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest req) {
        var uopt = users.findByEmail(req.email());
        if (uopt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "邮箱或密码错误"));
        }
        var u = uopt.get();
        if (!encoder.matches(req.password(), u.getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", "邮箱或密码错误"));
        }
        String token = jwt.issueToken(u.getId(), u.getEmail(), Map.of("type", "access"));
        return ResponseEntity.ok(new AuthResponse(token));
    }
}

record AuthRequest(String email, String password) {}
record RegisterRequest(String email, String password) {}
record AuthResponse(String accessToken) {}
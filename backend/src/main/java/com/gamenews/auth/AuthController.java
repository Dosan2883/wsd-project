package com.gamenews.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
    public record Credentials(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Size(max = 254, message = "이메일은 254자 이하로 입력해 주세요.")
        @Email(message = "올바른 이메일을 입력해 주세요.") String email,
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 72, message = "비밀번호는 8–72자로 입력해 주세요.") String password) {
        public Credentials {
            if (email != null) email = email.strip();
        }
    }

    private final AuthService auth;
    private final UserRepository users;
    public AuthController(AuthService auth, UserRepository users) { this.auth = auth; this.users = users; }

    @PostMapping("/auth/signup")
    ResponseEntity<AuthService.UserView> signup(@Valid @RequestBody Credentials request) {
        return ResponseEntity.created(URI.create("/api/me")).body(auth.signup(request.email(), request.password()));
    }

    @PostMapping("/auth/login")
    AuthService.LoginResult login(@Valid @RequestBody Credentials request) {
        return auth.login(request.email(), request.password());
    }

    @GetMapping("/me")
    AuthService.UserView me(@AuthenticationPrincipal Jwt jwt) {
        return AuthService.UserView.from(users.findById(Long.parseLong(jwt.getSubject())).orElseThrow());
    }

    @GetMapping("/admin/access")
    Map<String, String> adminAccess() { return Map.of("message", "관리자 권한이 확인되었습니다."); }

    @GetMapping("/health")
    Map<String, String> health() { return Map.of("status", "UP"); }
}

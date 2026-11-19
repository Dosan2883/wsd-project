package com.gamenews.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final long ttl;
    private final String dummyHash;

    public record UserView(Long id, String email, UserAccount.Role role, UserAccount.Status status, Instant createdAt) {
        static UserView from(UserAccount user) {
            return new UserView(user.getId(), user.getEmail(), user.getRole(), user.getStatus(), user.getCreatedAt());
        }
    }
    public record LoginResult(String accessToken, String tokenType, Instant expiresAt, UserView user) {}

    public AuthService(UserRepository users, PasswordEncoder passwords, JwtEncoder encoder,
                       @Value("${auth.issuer}") String issuer, @Value("${auth.audience}") String audience,
                       @Value("${auth.ttl-seconds}") long ttl) {
        this.users = users;
        this.passwords = passwords;
        this.encoder = encoder;
        this.issuer = issuer;
        this.audience = audience;
        if (ttl <= 0) throw new IllegalArgumentException("JWT TTL must be positive");
        this.ttl = ttl;
        this.dummyHash = passwords.encode("unused-dummy-password");
    }

    static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    static void validatePassword(String password) {
        // BCrypt only consumes 72 bytes; reject instead of silently truncating UTF-8 input.
        if (password.length() < 8 || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "비밀번호는 8자 이상, UTF-8 기준 72바이트 이하로 입력해 주세요.");
        }
    }

    public UserView signup(String email, String password) {
        validatePassword(password);
        try {
            return UserView.from(users.saveAndFlush(new UserAccount(normalizeEmail(email), passwords.encode(password), UserAccount.Role.USER)));
        } catch (DataIntegrityViolationException e) {
            // The database unique constraint also covers concurrent signups.
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다.");
        }
    }

    public LoginResult login(String email, String password) {
        validatePassword(password);
        var user = users.findByEmail(normalizeEmail(email)).orElse(null);
        boolean matches = passwords.matches(password, user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        if (user.getStatus() != UserAccount.Status.ACTIVE) {
            throw new AccountSuspendedException(user);
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttl);
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
            .subject(user.getId().toString()).issuedAt(now).expiresAt(expiresAt).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new LoginResult(token, "Bearer", expiresAt, UserView.from(user));
    }

    public UserView currentUser(Long id) {
        var user = users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
            "UNAUTHENTICATED", "유효하지 않은 계정입니다. 다시 로그인해 주세요."));
        if (user.getStatus() != UserAccount.Status.ACTIVE) throw new AccountSuspendedException(user);
        return UserView.from(user);
    }
}

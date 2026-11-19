package com.gamenews.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean SecretKey jwtKey(@Value("${auth.jwt-secret}") String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length < 32) throw new IllegalArgumentException("JWT_SECRET must be Base64 of at least 32 random bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }

    @Bean JwtDecoder jwtDecoder(SecretKey key, @Value("${auth.issuer}") String issuer, @Value("${auth.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> required = jwt -> {
            boolean valid = jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                && jwt.getIssuedAt().isBefore(jwt.getExpiresAt())
                && !jwt.getIssuedAt().isAfter(Instant.now())
                && jwt.getAudience() != null && jwt.getAudience().contains(audience)
                && jwt.getSubject() != null && jwt.getSubject().matches("[1-9][0-9]{0,18}");
            return valid ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(issuer), required));
        return decoder;
    }

    @Bean SecurityFilterChain security(HttpSecurity http, UserRepository users, ObjectMapper mapper) throws Exception {
        var entryPoint = (org.springframework.security.web.AuthenticationEntryPoint) (request, response, error) -> {
            if (error instanceof AccountSuspendedException suspended) {
                ApiErrors.writeSuspension(mapper, response, suspended);
                return;
            }
            response.setHeader("WWW-Authenticate", "Bearer");
            ApiErrors.write(mapper, response, 401, "UNAUTHENTICATED", "인증이 만료되었거나 유효하지 않습니다. 다시 로그인해 주세요.");
        };
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(rules -> rules
                .requestMatchers("/api/auth/signup", "/api/auth/login", "/api/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/articles", "/api/articles/{id}", "/api/games", "/api/games/steam").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint)
                .accessDeniedHandler((request, response, error) -> ApiErrors.write(mapper, response, 403, "FORBIDDEN", "이 페이지에 접근할 권한이 없습니다.")))
            .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(entryPoint)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                    UserAccount user;
                    try {
                        user = users.findById(Long.parseLong(token.getSubject())).orElseThrow(() -> new OAuth2AuthenticationException("invalid_token"));
                    } catch (NumberFormatException error) {
                        throw new OAuth2AuthenticationException("invalid_token");
                    }
                    if (user.getStatus() != UserAccount.Status.ACTIVE) throw new AccountSuspendedException(user);
                    return new JwtAuthenticationToken(token, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())), token.getSubject());
                }))).build();
    }
}

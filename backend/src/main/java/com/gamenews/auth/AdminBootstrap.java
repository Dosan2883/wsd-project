package com.gamenews.auth;

import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final String email;
    private final String password;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Validator validator;

    public AdminBootstrap(@Value("${auth.admin-email}") String email, @Value("${auth.admin-password}") String password,
                          UserRepository users, PasswordEncoder encoder, Validator validator) {
        this.email = email; this.password = password; this.users = users; this.encoder = encoder; this.validator = validator;
    }

    @Override public void run(ApplicationArguments args) {
        if (email.isEmpty() && password.isEmpty()) return;
        if (!validator.validate(new AuthController.Credentials(email, password)).isEmpty()) {
            throw new IllegalStateException("Both valid ADMIN_EMAIL and ADMIN_PASSWORD are required");
        }
        AuthService.validatePassword(password);
        var existing = users.findByEmail(AuthService.normalizeEmail(email));
        if (existing.isPresent()) {
            if (existing.get().getRole() != UserAccount.Role.ADMIN) throw new IllegalStateException("Admin bootstrap cannot promote an existing USER");
            return;
        }
        users.saveAndFlush(new UserAccount(AuthService.normalizeEmail(email), encoder.encode(password), UserAccount.Role.ADMIN));
    }
}

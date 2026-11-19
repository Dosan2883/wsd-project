package com.gamenews.auth;

import java.time.Instant;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

public class AccountSuspendedException extends OAuth2AuthenticationException {
    public record Suspension(String reason, Instant suspendedAt) {}
    private final Suspension suspension;

    public AccountSuspendedException(UserAccount user) {
        super(new OAuth2Error("account_suspended"), "정지된 계정입니다.");
        this.suspension = new Suspension(user.getSuspensionReason(), user.getSuspendedAt());
    }
    public Suspension getSuspension() { return suspension; }
}

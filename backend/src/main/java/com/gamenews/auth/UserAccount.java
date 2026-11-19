package com.gamenews.auth;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class UserAccount {
    public enum Role { USER, ADMIN }
    public enum Status { ACTIVE, SUSPENDED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 254, unique = true)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private Role role;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12)
    private Status status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;
    @Column(name = "suspended_at")
    private Instant suspendedAt;

    protected UserAccount() {}

    public UserAccount(String email, String passwordHash, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = Status.ACTIVE;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getSuspensionReason() { return suspensionReason; }
    public Instant getSuspendedAt() { return suspendedAt; }

    public void suspend(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("정지 사유는 1–500자로 입력해 주세요.");
        }
        if (status == Status.SUSPENDED) throw new IllegalStateException("이미 정지된 계정입니다.");
        this.status = Status.SUSPENDED;
        this.suspensionReason = reason.strip();
        this.suspendedAt = Instant.now();
    }

    public void restore() {
        this.status = Status.ACTIVE;
        this.suspensionReason = null;
        this.suspendedAt = null;
    }
    public void setRole(Role role) { this.role = role; }
}

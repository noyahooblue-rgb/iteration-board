package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "login_name", nullable = false, unique = true, length = 32)
    private String loginName;
    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(name = "avatar_color", length = 16)
    private String avatarColor;
    @Column(name = "platform_admin", nullable = false)
    private boolean platformAdmin;
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;
    @Column(name = "locked_until")
    private Instant lockedUntil;
    @Column(name = "password_version", nullable = false)
    private int passwordVersion;
    @Column(name = "preferences_json")
    private String preferencesJson;
    @Column(name = "deleted_at")
    private Instant deletedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getLoginName() { return loginName; }
    public void setLoginName(String v) { loginName = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { displayName = v; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { passwordHash = v; }
    public String getAvatarColor() { return avatarColor; }
    public void setAvatarColor(String v) { avatarColor = v; }
    public boolean isPlatformAdmin() { return platformAdmin; }
    public void setPlatformAdmin(boolean v) { platformAdmin = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean v) { mustChangePassword = v; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int v) { failedAttempts = v; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant v) { lockedUntil = v; }
    public int getPasswordVersion() { return passwordVersion; }
    public void setPasswordVersion(int v) { passwordVersion = v; }
    public String getPreferencesJson() { return preferencesJson; }
    public void setPreferencesJson(String v) { preferencesJson = v; }
    public Instant getCreatedAt() { return createdAt; }
}

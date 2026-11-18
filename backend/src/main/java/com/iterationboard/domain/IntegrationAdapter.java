package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 外部系统适配器：MOCK 内置样例 / HTTP 通用配置。config_json 中密钥只写不回显。 */
@Entity
@Table(name = "integration_adapter")
public class IntegrationAdapter {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false)
    private Team team;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 16)
    private String type;
    @Column(name = "system_name", nullable = false, length = 64)
    private String systemName;
    @Column(name = "config_json", nullable = false)
    private String configJson = "{}";
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "created_by", nullable = false)
    private AppUser createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team v) { team = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getType() { return type; }
    public void setType(String v) { type = v; }
    public String getSystemName() { return systemName; }
    public void setSystemName(String v) { systemName = v; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String v) { configJson = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int v) { consecutiveFailures = v; }
    public AppUser getCreatedBy() { return createdBy; }
    public void setCreatedBy(AppUser v) { createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

package com.iterationboard.domain;

import com.iterationboard.domain.Enums.Role;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "team_member", uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "user_id"}))
public class TeamMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false)
    private Team team;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Role role;
    @Column(name = "member_status", nullable = false, length = 16)
    private String memberStatus = "NORMAL";
    @Column(name = "status_start")
    private LocalDate statusStart;
    @Column(name = "status_end")
    private LocalDate statusEnd;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team v) { team = v; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser v) { user = v; }
    public Role getRole() { return role; }
    public void setRole(Role v) { role = v; }
    public String getMemberStatus() { return memberStatus; }
    public void setMemberStatus(String v) { memberStatus = v; }
    public LocalDate getStatusStart() { return statusStart; }
    public void setStatusStart(LocalDate v) { statusStart = v; }
    public LocalDate getStatusEnd() { return statusEnd; }
    public void setStatusEnd(LocalDate v) { statusEnd = v; }

    /** 成员状态在某日是否生效（请假/出差跳过催更、容量扣减用）：状态非 NORMAL 且日期范围覆盖该日（未填起止视为生效）。 */
    public boolean isAwayOn(LocalDate date) {
        if ("NORMAL".equals(memberStatus)) return false;
        if (statusStart != null && date.isBefore(statusStart)) return false;
        if (statusEnd != null && date.isAfter(statusEnd)) return false;
        return true;
    }
}

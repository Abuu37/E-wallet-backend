package com.application.e_wallet.user.entity;

import com.application.e_wallet.role.entity.RoleEntity;
import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import lombok.*;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.*;

@Slf4j
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_users_phone", columnNames = "phone")
        }
)
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserEntity implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Stable, opaque external identifier - self-generated if not supplied, same
    // pattern as other self-generating identifiers (agentNumber, etc.) in this codebase.
    @Column(name = "reference", nullable = false, unique = true)
    private UUID reference;

    // Human-chosen login/display handle. NOTE: the field is deliberately NOT named
    // "username" - Lombok's @Getter would then generate getUsername(), colliding with
    // the UserDetails-contract override below (which must keep returning id.toString()
    // for Spring Security's principal lookup). Naming it loginUsername keeps that
    // collision from happening silently; the value is read via getLoginUsername().
    @Column(name = "username", nullable = false, unique = true, length = 100)
    private String loginUsername;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(nullable = false, length = 100, unique = true)
    private String email;

    @Column(nullable = false, unique = true)
    private String phone;

    // Null while the account is PENDING/AWAITING_PIN; set once the user completes set-pin.
    @Column(name ="pin_hash")
    private String pinHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private RoleEntity role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = UserStatus.PENDING;
        }
        if (this.reference == null) {
            this.reference = UUID.randomUUID();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    @Override
    public@NonNull Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new HashSet<>();
        if (this.role != null) {
            // 1. Add the Role itself (e.g., ROLE_CUSTOMER)
            log.info("Role: {}", role);
            log.info("Permissions: {}", role.getPermissions());
            authorities.add(new SimpleGrantedAuthority(role.getName()));
            // 2. Add all associated Permissions (e.g., VENUE_READ, RIDE_REQUEST)
            role.getPermissions().forEach(permission -> authorities
                    .add(new SimpleGrantedAuthority(permission.getName())));
        }
        return authorities;
    }

    // Name fixed by the UserDetails contract; backed by the PIN hash.
    @Override
    public @Nullable String getPassword() {
        return pinHash;
    }

    // Name fixed by the UserDetails contract: this intentionally stays id.toString()
    // (not the human-chosen loginUsername) because Spring Security's principal lookup
    // keys on it. See the loginUsername field comment above for why the two don't collide.
    @Override
    public @NonNull String getUsername() {
        return id.toString();
    }

}

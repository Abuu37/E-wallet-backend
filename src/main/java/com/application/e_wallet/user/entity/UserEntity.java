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

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "middle_name", nullable = false, length = 100)
    private String middleName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String  lastName;

    @Column(nullable = false, length = 100, unique = true)
    private String email;

    @Column(nullable = false, unique = true)
    private String phone;

    @Column(name ="password_hash",nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserStatus status;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<RoleEntity> roles = new HashSet<>();

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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    @Override
    public@NonNull Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new HashSet<>();
        for (RoleEntity role : this.roles) {
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

    @Override
    public @Nullable String getPassword() {
        return passwordHash;
    }

    @Override
    public @NonNull String getUsername() {
        return id.toString();
    }

}

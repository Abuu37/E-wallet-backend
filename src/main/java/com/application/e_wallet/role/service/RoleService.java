package com.application.e_wallet.role.service;

import com.application.e_wallet.audit.service.AuditService;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import com.application.e_wallet.permission.entity.PermissionEntity;
import com.application.e_wallet.permission.repository.PermissionRepository;
import com.application.e_wallet.role.dto.ModuleGroup;
import com.application.e_wallet.role.dto.PermissionItem;
import com.application.e_wallet.role.dto.RoleItem;
import com.application.e_wallet.role.dto.RoleListResponse;
import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.role.repository.RoleRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService {

    private static final String ADMIN_ROLE = "ADMIN";
    /** Without these two nobody could open or change the Roles screen again. */
    private static final Set<String> LOCKOUT_PERMISSIONS = Set.of("ROLE_VIEW", "ROLE_ASSIGN");
    private static final Pattern ROLE_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{2,29}$");

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public RoleListResponse listRoles() {
        List<RoleItem> roles = roleRepository.findAll().stream()
                .sorted(Comparator.comparing(RoleEntity::getName))
                .map(this::toItem)
                .toList();

        Map<String, List<PermissionEntity>> byModule = permissionRepository.findAll().stream()
                .collect(Collectors.groupingBy(p -> p.getModule().getName()));
        List<ModuleGroup> modules = byModule.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new ModuleGroup(
                        e.getKey(),
                        e.getValue().get(0).getModule().getDescription(),
                        e.getValue().stream()
                                .sorted(Comparator.comparing(PermissionEntity::getName))
                                .map(p -> new PermissionItem(p.getName(), p.getDescription()))
                                .toList()))
                .toList();

        return new RoleListResponse(roles, modules);
    }

    /**
     * Replaces the permission set of a role. A change reaches signed-in users when their access token
     * is next refreshed (within 15 minutes).
     */
    @Transactional
    public RoleItem updatePermissions(UUID actorId, UUID roleId, Set<String> requested, String reason, String ip) {
        RoleEntity role = roleRepository.findById(roleId)
                .orElseThrow(() -> new BadRequestException("Role not found"));
        UserEntity actor = userRepository.findById(actorId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        Map<String, PermissionEntity> known = permissionRepository.findAll().stream()
                .collect(Collectors.toMap(PermissionEntity::getName, p -> p));
        List<String> unknown = requested.stream().filter(n -> !known.containsKey(n)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new BadRequestException("Unknown permission: " + String.join(", ", unknown));
        }

        if (ADMIN_ROLE.equals(role.getName()) && !requested.containsAll(LOCKOUT_PERMISSIONS)) {
            throw new BadRequestException("The ADMIN role must keep ROLE_VIEW and ROLE_ASSIGN.");
        }
        if (role.getId().equals(actor.getRole().getId()) && !requested.containsAll(LOCKOUT_PERMISSIONS)) {
            throw new BadRequestException("You cannot remove role access from your own role.");
        }

        List<String> before = names(role.getPermissions());
        role.getPermissions().clear();
        role.getPermissions().addAll(requested.stream().map(known::get).collect(Collectors.toCollection(HashSet::new)));
        RoleEntity saved = roleRepository.save(role);
        List<String> after = names(saved.getPermissions());

        auditService.record(actorId, "ROLE_PERMISSIONS_UPDATE", "ROLE", saved.getId(),
                Map.of("role", saved.getName(), "permissions", before),
                Map.of("role", saved.getName(), "permissions", after),
                reason, ip);
        return toItem(saved);
    }

    @Transactional
    public RoleItem createRole(UUID actorId, String rawName, String description, String ip) {
        String name = rawName == null ? "" : rawName.trim().toUpperCase();
        if (!ROLE_NAME.matcher(name).matches()) {
            throw new BadRequestException("Role name must be 3 to 30 characters: capital letters, digits and underscores, starting with a letter.");
        }
        if (roleRepository.existsByName(name)) {
            throw new DuplicationResourceException("A role named " + name + " already exists.");
        }
        String text = description == null || description.isBlank() ? name : description.trim();

        RoleEntity saved = roleRepository.save(RoleEntity.builder().name(name).description(text).build());
        auditService.record(actorId, "ROLE_CREATE", "ROLE", saved.getId(),
                null, Map.of("role", saved.getName(), "permissions", List.<String>of()), null, ip);
        return toItem(saved);
    }

    private RoleItem toItem(RoleEntity role) {
        List<String> permissions = names(role.getPermissions());
        long users = role.getId() == null ? 0 : userRepository.countByRoleId(role.getId());
        return new RoleItem(role.getId(), role.getName(), role.getDescription(), permissions.size(), users, permissions);
    }

    private static List<String> names(Set<PermissionEntity> permissions) {
        return permissions.stream().map(PermissionEntity::getName).sorted().toList();
    }
}

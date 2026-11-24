package com.application.e_wallet.role.service;

import com.application.e_wallet.audit.service.AuditService;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import com.application.e_wallet.module.entity.ModuleEntity;
import com.application.e_wallet.permission.entity.PermissionEntity;
import com.application.e_wallet.permission.repository.PermissionRepository;
import com.application.e_wallet.role.dto.RoleListResponse;
import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.role.repository.RoleRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleServiceTest {

    @Mock private RoleRepository roleRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @InjectMocks private RoleService service;

    private ModuleEntity roleModule;
    private ModuleEntity userModule;
    private PermissionEntity roleView, roleAssign, userView, userCreate;
    private RoleEntity admin, agent;
    private UserEntity actor;

    private static PermissionEntity perm(String name, ModuleEntity module) {
        return PermissionEntity.builder().id(UUID.randomUUID()).name(name).description(name).module(module).build();
    }

    @BeforeEach
    void setUp() {
        roleModule = ModuleEntity.builder().id(UUID.randomUUID()).name("ROLE").description("roles").build();
        userModule = ModuleEntity.builder().id(UUID.randomUUID()).name("USER").description("users").build();
        roleView = perm("ROLE_VIEW", roleModule);
        roleAssign = perm("ROLE_ASSIGN", roleModule);
        userView = perm("USER_VIEW", userModule);
        userCreate = perm("USER_CREATE", userModule);

        admin = RoleEntity.builder().id(UUID.randomUUID()).name("ADMIN").description("Admin")
                .permissions(new HashSet<>(Set.of(roleView, roleAssign, userView))).build();
        agent = RoleEntity.builder().id(UUID.randomUUID()).name("AGENT").description("Agent")
                .permissions(new HashSet<>(Set.of(userView))).build();
        actor = UserEntity.builder().id(UUID.randomUUID()).role(admin).build();

        when(roleRepository.findAll()).thenReturn(List.of(admin, agent));
        when(roleRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(roleRepository.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(admin));
        when(permissionRepository.findAll()).thenReturn(List.of(roleView, roleAssign, userView, userCreate));
        when(userRepository.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(userRepository.countByRoleId(admin.getId())).thenReturn(3L);
        when(userRepository.countByRoleId(agent.getId())).thenReturn(204L);
        when(roleRepository.save(any(RoleEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void listsRolesWithCountsAndPermissionsGroupedByModule() {
        RoleListResponse response = service.listRoles();

        assertThat(response.roles()).extracting("name").containsExactlyInAnyOrder("ADMIN", "AGENT");
        var adminItem = response.roles().stream().filter(r -> r.name().equals("ADMIN")).findFirst().orElseThrow();
        assertThat(adminItem.permissionCount()).isEqualTo(3);
        assertThat(adminItem.userCount()).isEqualTo(3);
        assertThat(adminItem.permissions()).containsExactlyInAnyOrder("ROLE_VIEW", "ROLE_ASSIGN", "USER_VIEW");
        assertThat(response.modules()).extracting("name").containsExactlyInAnyOrder("ROLE", "USER");
        var userGroup = response.modules().stream().filter(m -> m.name().equals("USER")).findFirst().orElseThrow();
        assertThat(userGroup.permissions()).extracting("name").containsExactlyInAnyOrder("USER_VIEW", "USER_CREATE");
    }

    @Test
    void replacesARolesPermissionsAndWritesAnAuditEntryWithBeforeAndAfter() {
        service.updatePermissions(actor.getId(), agent.getId(), Set.of("USER_VIEW", "USER_CREATE"), "Agents may create users", "10.0.0.1");

        assertThat(agent.getPermissions()).extracting(PermissionEntity::getName).containsExactlyInAnyOrder("USER_VIEW", "USER_CREATE");
        ArgumentCaptor<Object> before = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);
        verify(auditService).record(eq(actor.getId()), eq("ROLE_PERMISSIONS_UPDATE"), eq("ROLE"), eq(agent.getId()),
                before.capture(), after.capture(), eq("Agents may create users"), eq("10.0.0.1"));
        assertThat(before.getValue().toString()).contains("USER_VIEW").doesNotContain("USER_CREATE");
        assertThat(after.getValue().toString()).contains("USER_CREATE");
    }

    @Test
    void rejectsUnknownPermissionNames() {
        assertThatThrownBy(() -> service.updatePermissions(actor.getId(), agent.getId(), Set.of("DROP_TABLES"), null, "ip"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DROP_TABLES");
        verify(roleRepository, never()).save(any());
    }

    @Test
    void theAdminRoleCanNeverLoseRoleViewOrRoleAssign() {
        assertThatThrownBy(() -> service.updatePermissions(actor.getId(), admin.getId(), Set.of("ROLE_VIEW", "USER_VIEW"), null, "ip"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ROLE_ASSIGN");
        verify(roleRepository, never()).save(any());
        verify(auditService, never()).record(any(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void youCannotRemoveRoleAccessFromYourOwnRole() {
        RoleEntity custom = RoleEntity.builder().id(UUID.randomUUID()).name("OPS").description("Ops")
                .permissions(new HashSet<>(Set.of(roleView, roleAssign))).build();
        UserEntity opsUser = UserEntity.builder().id(UUID.randomUUID()).role(custom).build();
        when(roleRepository.findById(custom.getId())).thenReturn(Optional.of(custom));
        when(userRepository.findById(opsUser.getId())).thenReturn(Optional.of(opsUser));

        assertThatThrownBy(() -> service.updatePermissions(opsUser.getId(), custom.getId(), Set.of("ROLE_VIEW"), null, "ip"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("your own");
    }

    @Test
    void createsAnEmptyRoleAndAuditsIt() {
        when(roleRepository.existsByName("OPS_LEAD")).thenReturn(false);

        var created = service.createRole(actor.getId(), "ops_lead", "Operations lead", "10.0.0.1");

        assertThat(created.name()).isEqualTo("OPS_LEAD");
        assertThat(created.permissionCount()).isZero();
        verify(auditService).record(eq(actor.getId()), eq("ROLE_CREATE"), eq("ROLE"), any(), any(), any(), any(), eq("10.0.0.1"));
    }

    @Test
    void rejectsDuplicateAndMalformedRoleNames() {
        when(roleRepository.existsByName("AGENT")).thenReturn(true);

        assertThatThrownBy(() -> service.createRole(actor.getId(), "agent", "x", "ip")).isInstanceOf(DuplicationResourceException.class);
        assertThatThrownBy(() -> service.createRole(actor.getId(), "bad name!", "x", "ip")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.createRole(actor.getId(), "A", "x", "ip")).isInstanceOf(BadRequestException.class);
    }
}

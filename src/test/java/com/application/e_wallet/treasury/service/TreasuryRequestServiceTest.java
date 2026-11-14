package com.application.e_wallet.treasury.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.location.entity.DistrictEntity;
import com.application.e_wallet.location.entity.RegionEntity;
import com.application.e_wallet.staff.entity.StaffEntity;
import com.application.e_wallet.staff.entity.StaffStatus;
import com.application.e_wallet.staff.repository.StaffRepository;
import com.application.e_wallet.treasury.dto.TreasuryRequestCreateRequest;
import com.application.e_wallet.treasury.dto.TreasuryRequestDecisionRequest;
import com.application.e_wallet.treasury.dto.TreasuryRequestResponse;
import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.entity.RequestType;
import com.application.e_wallet.treasury.entity.TreasuryRequestEntity;
import com.application.e_wallet.treasury.repository.TreasuryRequestRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreasuryRequestServiceTest {

    @Mock private TreasuryRequestRepository treasuryRequestRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private UserRepository userRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TreasuryService treasuryService;
    @InjectMocks private TreasuryRequestService treasuryRequestService;

    private UUID requestId;
    private UUID adminUserId;
    private UUID agentId;
    private UUID agentUserId;
    private BigDecimal amount;
    private TreasuryRequestEntity request;

    @BeforeEach
    void setUp() {
        requestId = UUID.randomUUID();
        adminUserId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        agentUserId = UUID.randomUUID();
        amount = new BigDecimal("50.00");

        UserEntity admin = UserEntity.builder().id(adminUserId).build();
        UserEntity agentUser = UserEntity.builder().id(agentUserId).phone("255700000000").build();

        StaffEntity adminStaff = StaffEntity.builder()
                .user(admin)
                .firstName("Admin")
                .middleName("M")
                .lastName("User")
                .status(StaffStatus.ACTIVE)
                .build();

        RegionEntity region = RegionEntity.builder().id(UUID.randomUUID()).name("Dar es Salaam").build();
        DistrictEntity district = DistrictEntity.builder().id(UUID.randomUUID()).name("Ilala").build();

        AgentEntity agent = AgentEntity.builder()
                .id(agentId)
                .user(agentUser)
                .agentNumber("AG-001")
                .firstName("Agent")
                .middleName("M")
                .lastName("Doe")
                .region(region)
                .district(district)
                .build();

        request = TreasuryRequestEntity.builder()
                .id(requestId)
                .requestRef("REQ-ABC123")
                .agent(agent)
                .amount(amount)
                .reason("need float")
                .status(RequestStatus.PENDING)
                .requestType(RequestType.TOPUP)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        WalletEntity agentFloatWallet = WalletEntity.builder()
                .id(UUID.randomUUID())
                .walletNumber("WAL-001")
                .accountNumber("ACC-001")
                .build();

        lenient().when(treasuryRequestRepository.findByRequestId(requestId)).thenReturn(Optional.of(request));
        lenient().when(treasuryRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
        lenient().when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));
        lenient().when(staffRepository.findByUserId(adminUserId)).thenReturn(Optional.of(adminStaff));
        lenient().when(treasuryRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(walletRepository.findByUserIdAndWalletType(agentUserId, WalletType.AGENT_FLOAT))
                .thenReturn(Optional.of(agentFloatWallet));
    }

    @Test
    void createRequestPersistsExternalReferenceAndThreadsItToResponse() {
        when(agentRepository.findByUserId(agentUserId)).thenReturn(Optional.of(request.getAgent()));

        TreasuryRequestCreateRequest createRequest = new TreasuryRequestCreateRequest(
                RequestType.TOPUP, new BigDecimal("75.00"), "bank deposit", "BANK-REF-123");

        TreasuryRequestResponse response = treasuryRequestService.createRequest(agentUserId, createRequest);

        assertThat(response.externalReference()).isEqualTo("BANK-REF-123");
    }

    @Test
    void createRequestAllowsNullExternalReference() {
        when(agentRepository.findByUserId(agentUserId)).thenReturn(Optional.of(request.getAgent()));

        TreasuryRequestCreateRequest createRequest = new TreasuryRequestCreateRequest(
                RequestType.WITHDRAWAL, new BigDecimal("20.00"), "cash pickup", null);

        TreasuryRequestResponse response = treasuryRequestService.createRequest(agentUserId, createRequest);

        assertThat(response.externalReference()).isNull();
    }

    @Test
    void approveTopupRequestCallsTopup() {
        treasuryRequestService.approveRequest(requestId, adminUserId);
        verify(treasuryService).topup(eq(agentId), eq(amount), any(), eq(adminUserId));
    }

    @Test
    void approveWithdrawalRequestCallsWithdraw() {
        request.setRequestType(RequestType.WITHDRAWAL);

        treasuryRequestService.approveRequest(requestId, adminUserId);
        verify(treasuryService).withdraw(eq(agentId), eq(amount), any(), eq(adminUserId));
    }

    @Test
    void approveNonPendingRequestThrows() {
        request.setStatus(RequestStatus.APPROVED);

        assertThatThrownBy(() -> treasuryRequestService.approveRequest(requestId, adminUserId))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(treasuryService);
    }

    @Test
    void approveUsesTheLockedRepositoryQuery() {
        treasuryRequestService.approveRequest(requestId, adminUserId);

        verify(treasuryRequestRepository).findByRequestId(requestId);
        verify(treasuryRequestRepository, never()).findById(requestId);
    }

    @Test
    void rejectUsesTheLockedRepositoryQuery() {
        treasuryRequestService.rejectRequest(requestId, adminUserId, new TreasuryRequestDecisionRequest("not needed"));

        verify(treasuryRequestRepository).findByRequestId(requestId);
        verify(treasuryRequestRepository, never()).findById(requestId);
    }

    @Test
    void rejectRequiresAReason() {
        assertThatThrownBy(() -> treasuryRequestService.rejectRequest(
                requestId, adminUserId, new TreasuryRequestDecisionRequest("")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void getRequestUsesTheUnlockedLookupNotTheLockedQuery() {
        treasuryRequestService.getRequest(requestId, adminUserId, true);

        verify(treasuryRequestRepository).findById(requestId);
        verify(treasuryRequestRepository, never()).findByRequestId(requestId);
    }
}

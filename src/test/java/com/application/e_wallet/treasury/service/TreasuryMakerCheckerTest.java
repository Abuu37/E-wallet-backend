package com.application.e_wallet.treasury.service;

import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.staff.repository.StaffRepository;
import com.application.e_wallet.treasury.dto.MintRequest;
import com.application.e_wallet.treasury.dto.TreasuryRequestDecisionRequest;
import com.application.e_wallet.treasury.dto.TreasuryRequestResponse;
import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.entity.RequestType;
import com.application.e_wallet.treasury.entity.TreasuryRequestEntity;
import com.application.e_wallet.treasury.repository.TreasuryRequestRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Mint and commission top-up need two different staff members: the requester can never decide. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TreasuryMakerCheckerTest {

    @Mock private TreasuryRequestRepository treasuryRequestRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private UserRepository userRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TreasuryService treasuryService;
    @InjectMocks private TreasuryRequestService service;

    private UserEntity maker;
    private UserEntity checker;
    private UUID requestId;

    @BeforeEach
    void setUp() {
        maker = UserEntity.builder().id(UUID.randomUUID()).email("maker@example.com").build();
        checker = UserEntity.builder().id(UUID.randomUUID()).email("checker@example.com").build();
        requestId = UUID.randomUUID();
        when(userRepository.findById(maker.getId())).thenReturn(Optional.of(maker));
        when(userRepository.findById(checker.getId())).thenReturn(Optional.of(checker));
        when(treasuryRequestRepository.save(any(TreasuryRequestEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    private TreasuryRequestEntity pending(RequestType type) {
        return TreasuryRequestEntity.builder()
                .id(requestId).requestRef("REQ-TEST0001").requestType(type)
                .amount(new BigDecimal("5000000.00")).reason("Bank deposit slip 118")
                .status(RequestStatus.PENDING).requestedBy(maker).build();
    }

    @Test
    void creatingAMintRequestOnlyRecordsItAndMovesNoMoney() {
        TreasuryRequestResponse response = service.createStaffRequest(
                maker.getId(), RequestType.MINT, new BigDecimal("5000000.00"), "Bank deposit slip 118");

        ArgumentCaptor<TreasuryRequestEntity> saved = ArgumentCaptor.forClass(TreasuryRequestEntity.class);
        verify(treasuryRequestRepository).save(saved.capture());
        assertThat(saved.getValue().getRequestType()).isEqualTo(RequestType.MINT);
        assertThat(saved.getValue().getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(saved.getValue().getRequestedBy()).isEqualTo(maker);
        assertThat(saved.getValue().getAgent()).isNull();
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.requestedByUserId()).isEqualTo(maker.getId());
        verifyNoInteractions(treasuryService);
    }

    @Test
    void agentFloatTypesCannotBeCreatedThroughTheStaffPath() {
        assertThatThrownBy(() -> service.createStaffRequest(maker.getId(), RequestType.TOPUP, BigDecimal.TEN, "x"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void theRequesterCannotApproveTheirOwnMint() {
        when(treasuryRequestRepository.findByRequestId(requestId)).thenReturn(Optional.of(pending(RequestType.MINT)));

        assertThatThrownBy(() -> service.approveRequest(requestId, maker.getId()))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(treasuryService);
        verify(treasuryRequestRepository, never()).save(any());
    }

    @Test
    void theRequesterCannotRejectTheirOwnCommissionTopup() {
        when(treasuryRequestRepository.findByRequestId(requestId)).thenReturn(Optional.of(pending(RequestType.COMMISSION_TOPUP)));

        assertThatThrownBy(() -> service.rejectRequest(requestId, maker.getId(), new TreasuryRequestDecisionRequest("changed my mind")))
                .isInstanceOf(AccessDeniedException.class);

        verify(treasuryRequestRepository, never()).save(any());
    }

    @Test
    void aDifferentStaffMemberApprovingPostsTheMint() {
        TreasuryRequestEntity request = pending(RequestType.MINT);
        when(treasuryRequestRepository.findByRequestId(requestId)).thenReturn(Optional.of(request));

        TreasuryRequestResponse response = service.approveRequest(requestId, checker.getId());

        ArgumentCaptor<MintRequest> mint = ArgumentCaptor.forClass(MintRequest.class);
        verify(treasuryService).mint(mint.capture(), eq(checker.getId()));
        assertThat(mint.getValue().amount()).isEqualByComparingTo("5000000.00");
        assertThat(mint.getValue().description()).isEqualTo("Bank deposit slip 118");
        assertThat(request.getStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(request.getDecidedBy()).isEqualTo(checker);
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    @Test
    void aDifferentStaffMemberApprovingFundsTheCommissionPool() {
        TreasuryRequestEntity request = pending(RequestType.COMMISSION_TOPUP);
        when(treasuryRequestRepository.findByRequestId(requestId)).thenReturn(Optional.of(request));

        service.approveRequest(requestId, checker.getId());

        verify(treasuryService).fundCommission(eq(new BigDecimal("5000000.00")), eq("Bank deposit slip 118"), eq(checker.getId()));
        assertThat(request.getStatus()).isEqualTo(RequestStatus.APPROVED);
    }

    @Test
    void aDifferentStaffMemberCanRejectWithANote() {
        TreasuryRequestEntity request = pending(RequestType.MINT);
        when(treasuryRequestRepository.findByRequestId(requestId)).thenReturn(Optional.of(request));

        service.rejectRequest(requestId, checker.getId(), new TreasuryRequestDecisionRequest("Slip does not match the bank statement"));

        assertThat(request.getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(request.getDecisionNote()).isEqualTo("Slip does not match the bank statement");
        verifyNoInteractions(treasuryService);
    }
}

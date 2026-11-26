package com.application.e_wallet.treasury.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.storage.SlipStorage;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.treasury.dto.FloatRequestResponse;
import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.entity.RequestType;
import com.application.e_wallet.treasury.entity.TreasuryRequestEntity;
import com.application.e_wallet.treasury.repository.TreasuryRequestRepository;
import com.application.e_wallet.user.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Float request with a slip (Path B): what is accepted and what is refused before staff ever see it. */
@ExtendWith(MockitoExtension.class)
class FloatRequestServiceTest {

    @Mock private AgentRepository agentRepository;
    @Mock private TreasuryRequestRepository requests;
    @Mock private SlipStorage slips;
    @Mock private Notifier notifier;
    @InjectMocks private FloatRequestService service;

    private UserEntity user;
    private AgentEntity agent;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).phone("+255744000111").build();
        agent = AgentEntity.builder().id(UUID.randomUUID()).user(user).agentNumber("AG-10234").agentStatus(AgentStatus.ACTIVE).build();
        lenient().when(agentRepository.findByUserId(user.getId())).thenReturn(Optional.of(agent));
        lenient().when(requests.countByAgent_IdAndStatus(agent.getId(), RequestStatus.PENDING)).thenReturn(0L);
        lenient().when(requests.existsByExternalReference(any())).thenReturn(false);
        lenient().when(slips.save(any())).thenReturn(new SlipStorage.Stored("a".repeat(32) + ".jpg", "image/jpeg"));
        lenient().when(requests.save(any(TreasuryRequestEntity.class))).thenAnswer(inv -> {
            TreasuryRequestEntity r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            r.setRequestRef("REQ-ABCD1234");
            return r;
        });
    }

    @Test
    void aValidRequestIsPendingWithTheSlipAndTheAgentIsToldWeGotIt() {
        FloatRequestResponse response = service.create(user.getId(), new BigDecimal("500000"), "SLIP-7781", new byte[]{1, 2, 3});

        ArgumentCaptor<TreasuryRequestEntity> saved = ArgumentCaptor.forClass(TreasuryRequestEntity.class);
        verify(requests).save(saved.capture());
        assertThat(saved.getValue().getRequestType()).isEqualTo(RequestType.TOPUP);
        assertThat(saved.getValue().getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(saved.getValue().getExternalReference()).isEqualTo("SLIP-7781");
        assertThat(saved.getValue().getSlipFileKey()).endsWith(".jpg");
        assertThat(saved.getValue().getRequestedBy()).isEqualTo(user);
        assertThat(response.status()).isEqualTo("PENDING");
        verify(notifier).notify(eq(NotificationKey.A4), any(), any());
    }

    @Test
    void theEscrowDetailsCarryTheAgentNumberAsTheReference() {
        assertThat(service.escrowDetails(user.getId()).reference()).isEqualTo("AG-10234");
    }

    @Test
    void onlyOneOpenRequestAtATime() {
        lenient().when(requests.countByAgent_IdAndStatus(agent.getId(), RequestStatus.PENDING)).thenReturn(1L);
        assertThatThrownBy(() -> service.create(user.getId(), new BigDecimal("500000"), "SLIP-7781", new byte[]{1}))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already have a float request");
        verifyNoInteractions(notifier, slips);
    }

    @Test
    void aSlipNumberCannotBeUsedTwice() {
        lenient().when(requests.existsByExternalReference("SLIP-7781")).thenReturn(true);
        assertThatThrownBy(() -> service.create(user.getId(), new BigDecimal("500000"), "SLIP-7781", new byte[]{1}))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already used");
        verifyNoInteractions(slips);
    }

    @Test
    void theAmountAndTheSlipNumberMustBeSensible() {
        assertThatThrownBy(() -> service.create(user.getId(), BigDecimal.ZERO, "SLIP-7781", new byte[]{1})).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(user.getId(), new BigDecimal("100.555"), "SLIP-7781", new byte[]{1})).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(user.getId(), new BigDecimal("1000"), "ab", new byte[]{1})).isInstanceOf(BadRequestException.class).hasMessageContaining("slip");
        verifyNoInteractions(slips, notifier);
    }

    @Test
    void aSuspendedAgentCannotAsk() {
        agent.setAgentStatus(AgentStatus.SUSPENDED);
        assertThatThrownBy(() -> service.create(user.getId(), new BigDecimal("1000"), "SLIP-7781", new byte[]{1})).isInstanceOf(BadRequestException.class).hasMessageContaining("not active");
    }

    @Test
    void theSlipIsVisibleToStaffAndToItsAgentOnly() {
        TreasuryRequestEntity request = TreasuryRequestEntity.builder().id(UUID.randomUUID()).agent(agent).slipFileKey("b".repeat(32) + ".png").slipContentType("image/png").build();
        lenient().when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        lenient().when(slips.load(request.getSlipFileKey(), "image/png")).thenReturn(new SlipStorage.Loaded(new byte[]{1}, "image/png"));

        assertThat(service.slip(request.getId(), user.getId(), false).contentType()).isEqualTo("image/png");
        assertThat(service.slip(request.getId(), UUID.randomUUID(), true).contentType()).isEqualTo("image/png");
        assertThatThrownBy(() -> service.slip(request.getId(), UUID.randomUUID(), false)).isInstanceOf(BadRequestException.class).hasMessage("Slip not found");
    }
}

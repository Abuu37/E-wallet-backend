package com.application.e_wallet.common.idempotency;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    record Body(String account, BigDecimal amount) { }

    record Receipt(String reference, BigDecimal amount) { }

    @Mock private IdempotencyKeyRepository repository;
    @Mock private PlatformTransactionManager transactionManager;

    private IdempotencyService service;
    private final UUID agent = UUID.randomUUID();
    private final Body cashIn = new Body("WP-0712-345678", new BigDecimal("50000"));
    private final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository, transactionManager, JsonMapper.builder().build());
        lenient().when(repository.saveAndFlush(any(IdempotencyKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Receipt post(String key, Body body) {
        return service.execute(agent, key, "POST /transactions/cash-in", body, Receipt.class,
                () -> { runs.incrementAndGet(); return new Receipt("CIN-1", body.amount()); });
    }

    private IdempotencyKeyEntity stored(String hashOf, String status, String json) {
        // Run once to learn the real hash, then rebuild the row a previous request would have left.
        return IdempotencyKeyEntity.builder().userId(agent).idemKey("k1").endpoint("POST /transactions/cash-in")
                .requestHash(hashOf).status(status).responseJson(json).build();
    }

    @Test
    void aNewKeyRunsTheActionOnceAndStoresTheAnswer() {
        when(repository.findByUserIdAndIdemKey(agent, "k1")).thenReturn(Optional.empty());

        Receipt receipt = post("k1", cashIn);

        assertThat(receipt.reference()).isEqualTo("CIN-1");
        assertThat(runs.get()).isEqualTo(1);
        verify(repository).saveAndFlush(any(IdempotencyKeyEntity.class));
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(row ->
                IdempotencyKeyEntity.COMPLETED.equals(row.getStatus()) && row.getResponseJson().contains("CIN-1")));
    }

    @Test
    void theSameKeyAndBodyReturnsTheStoredAnswerWithoutRunningAgain() {
        // First request: capture the row it stores.
        when(repository.findByUserIdAndIdemKey(agent, "k1")).thenReturn(Optional.empty());
        org.mockito.ArgumentCaptor<IdempotencyKeyEntity> captor = org.mockito.ArgumentCaptor.forClass(IdempotencyKeyEntity.class);
        post("k1", cashIn);
        verify(repository).save(captor.capture());
        IdempotencyKeyEntity row = captor.getValue();

        // Retry with the same key and body.
        when(repository.findByUserIdAndIdemKey(agent, "k1")).thenReturn(Optional.of(row));
        Receipt again = post("k1", cashIn);

        assertThat(again.reference()).isEqualTo("CIN-1");
        assertThat(runs.get()).as("the money flow must not run twice").isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentBodyIsAConflict() {
        when(repository.findByUserIdAndIdemKey(agent, "k1")).thenReturn(Optional.empty());
        org.mockito.ArgumentCaptor<IdempotencyKeyEntity> captor = org.mockito.ArgumentCaptor.forClass(IdempotencyKeyEntity.class);
        post("k1", cashIn);
        verify(repository).save(captor.capture());
        when(repository.findByUserIdAndIdemKey(agent, "k1")).thenReturn(Optional.of(captor.getValue()));

        assertThatThrownBy(() -> post("k1", new Body("WP-0712-345678", new BigDecimal("60000"))))
                .isInstanceOf(DuplicationResourceException.class).hasMessageContaining("different request");
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void theSameKeyOnAnotherEndpointIsAConflict() {
        when(repository.findByUserIdAndIdemKey(agent, "k1")).thenReturn(Optional.empty());
        org.mockito.ArgumentCaptor<IdempotencyKeyEntity> captor = org.mockito.ArgumentCaptor.forClass(IdempotencyKeyEntity.class);
        post("k1", cashIn);
        verify(repository).save(captor.capture());
        when(repository.findByUserIdAndIdemKey(agent, "k1")).thenReturn(Optional.of(captor.getValue()));

        assertThatThrownBy(() -> service.execute(agent, "k1", "POST /transactions/cash-out", cashIn, Receipt.class,
                () -> new Receipt("WDR-1", cashIn.amount())))
                .isInstanceOf(DuplicationResourceException.class);
    }

    @Test
    void aRequestStillInProgressAsksTheCallerToWait() {
        when(repository.findByUserIdAndIdemKey(agent, "k1")).thenReturn(Optional.empty());
        org.mockito.ArgumentCaptor<IdempotencyKeyEntity> captor = org.mockito.ArgumentCaptor.forClass(IdempotencyKeyEntity.class);
        post("k1", cashIn);
        verify(repository).save(captor.capture());
        IdempotencyKeyEntity row = captor.getValue();
        row.setStatus(IdempotencyKeyEntity.IN_PROGRESS);
        row.setResponseJson(null);
        when(repository.findByUserIdAndIdemKey(agent, "k1")).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> post("k1", cashIn)).isInstanceOf(DuplicationResourceException.class).hasMessageContaining("still being processed");
    }

    @Test
    void aFailedActionStoresNothingSoTheSameKeyCanBeRetried() {
        when(repository.findByUserIdAndIdemKey(agent, "k1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(agent, "k1", "POST /transactions/cash-in", cashIn, Receipt.class,
                () -> { throw new BadRequestException("Insufficient float"); }))
                .isInstanceOf(BadRequestException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void twoRequestsRacingOnANewKeyEndWithOneRunAndOneReplay() {
        when(repository.findByUserIdAndIdemKey(agent, "k1")).thenReturn(Optional.empty());
        org.mockito.ArgumentCaptor<IdempotencyKeyEntity> captor = org.mockito.ArgumentCaptor.forClass(IdempotencyKeyEntity.class);
        post("k1", cashIn);
        verify(repository).save(captor.capture());
        IdempotencyKeyEntity winner = captor.getValue();

        // The loser: its insert collides, then its second attempt finds the winner's committed row.
        when(repository.findByUserIdAndIdemKey(agent, "k1")).thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any(IdempotencyKeyEntity.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        Receipt replay = post("k1", cashIn);

        assertThat(replay.reference()).isEqualTo("CIN-1");
        assertThat(runs.get()).as("the loser never ran the money flow: its insert collided first").isEqualTo(1);
    }

    @Test
    void aMissingOrBlankKeyIsRefused() {
        assertThatThrownBy(() -> post(null, cashIn)).isInstanceOf(BadRequestException.class).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> post("  ", cashIn)).isInstanceOf(BadRequestException.class);
        assertThat(runs.get()).isZero();
    }

    @Test
    void aTooLongKeyIsRefused() {
        assertThatThrownBy(() -> post("x".repeat(81), cashIn)).isInstanceOf(BadRequestException.class).hasMessageContaining("80");
    }
}

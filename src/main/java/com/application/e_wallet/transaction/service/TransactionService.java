package com.application.e_wallet.transaction.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.tariff.entity.TariffEntity;
import com.application.e_wallet.tariff.service.TariffService;
import com.application.e_wallet.transaction.dto.CashInRequest;
import com.application.e_wallet.transaction.dto.CashInResponse;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.entity.TransactionStatus;
import com.application.e_wallet.transaction.entity.TransactionType;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final DateTimeFormatter REFERENCE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AgentRepository agentRepository;
    private final CustomerRepository customerRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final TariffService tariffService;
    private final LedgerService ledgerService;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final PasswordEncoder pinEncoder;

    //=================== AGENT CASH-IN: CUSTOMER GIVE CASH TO AGENT FOR THE WALLET ADDITION ==============
    @Transactional
    public CashInResponse cashIn(CashInRequest request, UUID userId) {

        AgentEntity agent = agentRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Agent account not found"));

        if (agent.getAgentStatus() != AgentStatus.ACTIVE) {
            throw new BadRequestException("Agent is not active");
        }

        UserEntity agentUser = agent.getUser();

        WalletEntity agentFloatWallet = walletRepository.findByUserIdAndWalletTypeForUpdate(agentUser.getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new BadRequestException("Agent float wallet not found"));

        if (!agentFloatWallet.getAccountNumber().equals(request.agentAccountNumber())) {
            throw new BadRequestException("Agent account number does not match the authenticated agent");
        }

        if (agentFloatWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("Agent float wallet is not active");
        }

        if (!pinEncoder.matches(request.agentPin(), agentUser.getPinHash())) {
            throw new BadRequestException("Invalid agent PIN");
        }

        WalletEntity customerWallet = walletRepository.findByAccountNumberForUpdate(request.accountNumber())
                .orElseThrow(() -> new BadRequestException("Customer account not found"));

        if (customerWallet.getWalletType() != WalletType.CUSTOMER_MAIN) {
            throw new BadRequestException("Account number does not belong to a customer wallet");
        }

        UserEntity customerUser = customerWallet.getUser();
        CustomerEntity customer = customerRepository.findByUserId(customerUser.getId())
                .orElseThrow(() -> new BadRequestException("Customer not found"));

        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw new BadRequestException("Customer account is not active");
        }

        String normalizedPhone = phoneNumberNormalizer.normalize(request.phone());
        if (!customer.getPhoneNumber().equals(normalizedPhone)) {
            throw new BadRequestException("Phone number does not match the account holder");
        }

        BigDecimal amount = request.amount();

        TariffEntity tariff = tariffService.findApplicableTariff(TransactionType.CASH_IN.name(), amount);
        BigDecimal fee = tariff.getFee();
        BigDecimal commission = tariff.getAgentCommission();

        WalletEntity commissionPoolWallet = walletRepository.findByWalletTypeForUpdate(WalletType.COMMISSION)
                .orElseThrow(() -> new IllegalStateException("Commission wallet not initialized"));
        WalletEntity agentCommissionWallet = walletRepository.findByUserIdAndWalletTypeForUpdate(agentUser.getId(), WalletType.AGENT_COMMISSION)
                .orElseThrow(() -> new BadRequestException("Agent commission wallet not found"));

        if (customerWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("Customer wallet is not active");
        }

        if (agentFloatWallet.getBalance().compareTo(amount) < 0) {
            throw new BadRequestException("Insufficient agent float balance");
        }
        if (commissionPoolWallet.getBalance().compareTo(commission) < 0) {
            throw new BadRequestException("Insufficient commission pool balance");
        }

        agentFloatWallet.setBalance(agentFloatWallet.getBalance().subtract(amount));
        customerWallet.setBalance(customerWallet.getBalance().add(amount));
        commissionPoolWallet.setBalance(commissionPoolWallet.getBalance().subtract(commission));
        agentCommissionWallet.setBalance(agentCommissionWallet.getBalance().add(commission));

        ledgerService.postJournal(LedgerTransactionType.CASH_IN,
                "Agent cash-in for " + customer.getPhoneNumber(), agentUser, List.of(
                        new JournalLineRequest(agentFloatWallet.getId(), EntryType.DEBIT, amount),
                        new JournalLineRequest(customerWallet.getId(), EntryType.CREDIT, amount),
                        new JournalLineRequest(commissionPoolWallet.getId(), EntryType.DEBIT, commission),
                        new JournalLineRequest(agentCommissionWallet.getId(), EntryType.CREDIT, commission)
                ));

        walletRepository.save(agentFloatWallet);
        walletRepository.save(customerWallet);
        walletRepository.save(commissionPoolWallet);
        walletRepository.save(agentCommissionWallet);

        String referenceNo = generateReferenceNo();

        TransactionEntity transaction = TransactionEntity.builder()
                .referenceNo(referenceNo)
                .senderWallet(agentFloatWallet)
                .receiverWallet(customerWallet)
                .amount(amount)
                .fee(fee)
                .commission(commission)
                .transactionType(TransactionType.CASH_IN.name())
                .status(TransactionStatus.COMPLETED.name())
                .build();

        transactionRepository.save(transaction);

        return new CashInResponse(
                referenceNo,
                TransactionStatus.COMPLETED.name(),
                customerWallet.getCurrency(),
                amount,
                fee,
                commission,
                new CashInResponse.CustomerLeg(customer.getPhoneNumber(), customerWallet.getBalance()),
                new CashInResponse.AgentLeg(agentFloatWallet.getAccountNumber(), agentFloatWallet.getBalance(), agentCommissionWallet.getBalance()),
                OffsetDateTime.now()
        );
    }

    private String generateReferenceNo() {
        String referenceNo;
        do {
            String datePart = OffsetDateTime.now().format(REFERENCE_DATE_FORMAT);
            String randomPart = UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 8)
                    .toUpperCase();
            referenceNo = "TXN-" + datePart + "-" + randomPart;
        } while (transactionRepository.existsByReferenceNo(referenceNo));

        return referenceNo;
    }
}

package com.application.e_wallet.bank.service;

import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.bank.dto.BankBalanceResponse;
import com.application.e_wallet.bank.dto.LinkedBankResponse;
import com.application.e_wallet.bank.entity.LinkedBankEntity;
import com.application.e_wallet.bank.repository.LinkedBankRepository;
import com.application.e_wallet.common.crypto.TokenCipher;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.integration.plaid.PlaidBankApi;
import com.application.e_wallet.integration.plaid.PlaidProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Links a person's own bank through Plaid and decides whether it may be used. A bank is usable only when the name
 * on the account matches the customer's name (or the agent's business name). In Sandbox the check can be skipped,
 * and the response then says so, so it is never mistaken for a real match.
 */
@Service
@RequiredArgsConstructor
public class BankLinkService {

    public static final String MATCHED = "MATCHED";
    public static final String SKIPPED_SANDBOX = "SKIPPED_SANDBOX";
    public static final String MISMATCH = "MISMATCH";

    private final PlaidBankApi plaid;
    private final PlaidProperties properties;
    private final LinkedBankRepository banks;
    private final CustomerRepository customers;
    private final AgentRepository agents;
    private final TokenCipher cipher;

    public String linkToken(UUID userId) {
        return plaid.createLinkToken(userId.toString());
    }

    @Transactional
    public LinkedBankResponse exchange(UUID userId, String publicToken, String accountId) {
        PlaidBankApi.Exchanged exchanged = plaid.exchange(publicToken);
        List<PlaidBankApi.BankAccount> usable = plaid.accounts(exchanged.accessToken()).stream()
                .filter(a -> "depository".equals(a.type()) && List.of("checking", "savings").contains(a.subtype())).toList();
        String wanted = accountId;
        PlaidBankApi.BankAccount account = (wanted == null || wanted.isBlank()
                ? usable.stream().findFirst()
                : usable.stream().filter(a -> a.id().equals(wanted)).findFirst())
                .orElseThrow(() -> new BadRequestException("Link a checking or savings account from your bank."));

        List<String> owners = plaid.ownerNames(exchanged.accessToken(), account.id());
        String expected = expectedName(userId);
        boolean matched = NameMatcher.matchesAny(expected, owners);

        LinkedBankEntity bank = banks.findByUserIdAndProviderAccountId(userId, account.id()).orElseGet(LinkedBankEntity::new);
        bank.setUserId(userId);
        bank.setProviderItemId(exchanged.itemId());
        bank.setAccessTokenEncrypted(cipher.encrypt(exchanged.accessToken()));
        bank.setProviderAccountId(account.id());
        bank.setInstitutionName(plaid.institutionName(exchanged.accessToken()));
        bank.setAccountName(account.name());
        bank.setAccountMask(account.mask());
        bank.setHolderName(owners.isEmpty() ? null : owners.get(0));
        bank.setNameMatched(matched);
        bank.setStatus("ACTIVE");
        return toResponse(banks.save(bank));
    }

    @Transactional(readOnly = true)
    public List<LinkedBankResponse> list(UUID userId) {
        return banks.findByUserIdAndStatusOrderByCreatedAtDesc(userId, "ACTIVE").stream().map(this::toResponse).toList();
    }

    @Transactional
    public void unlink(UUID userId, UUID bankId) {
        LinkedBankEntity bank = ownBank(userId, bankId);
        plaid.removeItem(cipher.decrypt(bank.getAccessTokenEncrypted()));
        bank.setStatus("REVOKED");
        banks.save(bank);
    }

    @Transactional(readOnly = true)
    public BankBalanceResponse balance(UUID userId, UUID bankId) {
        LinkedBankEntity bank = requireUsable(ownBank(userId, bankId));
        BigDecimal usd = plaid.availableBalance(cipher.decrypt(bank.getAccessTokenEncrypted()), bank.getProviderAccountId());
        BigDecimal rate = properties.getSandbox().getUsdToTzs();
        return new BankBalanceResponse(usd.multiply(rate).setScale(2, RoundingMode.DOWN), usd, rate, properties.isSandbox());
    }

    // ---------------------------------------------------------------- used by the money flows
    public LinkedBankEntity ownBank(UUID userId, UUID bankId) {
        return banks.findByIdAndUserIdAndStatus(bankId, userId, "ACTIVE")
                .orElseThrow(() -> new BadRequestException("This bank is not linked to your account."));
    }

    /** Throws unless the bank may be used for money: the name matches, or the Sandbox override is on. */
    public LinkedBankEntity requireUsable(LinkedBankEntity bank) {
        if (!MISMATCH.equals(nameCheck(bank))) {
            return bank;
        }
        throw new BadRequestException("The name on this bank account does not match your name on WalletPesa, so it cannot be used.");
    }

    public String nameCheck(LinkedBankEntity bank) {
        if (bank.isNameMatched()) {
            return MATCHED;
        }
        return properties.skipNameMatchActive() ? SKIPPED_SANDBOX : MISMATCH;
    }

    public String accessToken(LinkedBankEntity bank) {
        return cipher.decrypt(bank.getAccessTokenEncrypted());
    }

    /** The bank's available balance in TZS at the demo rate. */
    public BigDecimal availableTzs(LinkedBankEntity bank) {
        BigDecimal usd = plaid.availableBalance(accessToken(bank), bank.getProviderAccountId());
        return usd.multiply(properties.getSandbox().getUsdToTzs());
    }

    /** TZS to the bank's own currency (USD in Sandbox), rounded up so the bank side is never short. */
    public BigDecimal toBankCurrency(BigDecimal tzs) {
        return tzs.divide(properties.getSandbox().getUsdToTzs(), 2, RoundingMode.UP);
    }

    private String expectedName(UUID userId) {
        return customers.findByUserId(userId)
                .map(c -> c.getFirstName().trim() + " " + c.getLastName().trim())
                .or(() -> agents.findByUserId(userId).map(a -> a.getBusinessName()))
                .orElseThrow(() -> new BadRequestException("Only customers and agents can link a bank."));
    }

    private LinkedBankResponse toResponse(LinkedBankEntity bank) {
        String check = nameCheck(bank);
        return new LinkedBankResponse(bank.getId(), bank.getInstitutionName(), bank.getAccountName(), bank.getAccountMask(), check, !MISMATCH.equals(check));
    }
}

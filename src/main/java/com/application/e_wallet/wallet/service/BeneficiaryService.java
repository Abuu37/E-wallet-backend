package com.application.e_wallet.wallet.service;

import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.dto.BeneficiaryItem;
import com.application.e_wallet.wallet.entity.BeneficiaryEntity;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.repository.BeneficiaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BeneficiaryService {

    private final BeneficiaryRepository repository;
    private final CustomerRepository customerRepository;

    /** Called after a successful send: add the recipient, or move them to the top. */
    @Transactional
    public void remember(UserEntity owner, WalletEntity recipientWallet) {
        BeneficiaryEntity existing = repository.findByOwnerIdAndWalletId(owner.getId(), recipientWallet.getId()).orElse(null);
        if (existing == null) {
            repository.save(BeneficiaryEntity.builder().owner(owner).wallet(recipientWallet).lastUsedAt(OffsetDateTime.now()).build());
        } else {
            existing.setLastUsedAt(OffsetDateTime.now());
            repository.save(existing);
        }
    }

    @Transactional(readOnly = true)
    public List<BeneficiaryItem> recent(UUID ownerId) {
        return repository.findRecent(ownerId).stream()
                .map(b -> new BeneficiaryItem(
                        customerRepository.findByUserId(b.getWallet().getUser().getId())
                                .map(c -> c.getFirstName().trim() + " " + c.getLastName().trim()).orElse("WalletPesa user"),
                        b.getWallet().getUser().getPhone(), b.getLastUsedAt()))
                .toList();
    }
}

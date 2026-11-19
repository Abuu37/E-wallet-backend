package com.application.e_wallet.auth.dto;

import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Builder
public class UserProfileResponse {

    private UUID customerId;
    private String firstName;
    private String middleName;
    private String lastName;
    private String phoneNumber;
    private String email;
    private String nidaNumber;
    private LocalDate dateOfBirth;
    private String address;
    private UserStatus status;
    private WalletSummary wallet;
    private Instant createdAt;

    @Getter
    @Setter
    @Builder
    public static class WalletSummary {
        private String accountNumber;
        private WalletType walletType;
        private BigDecimal balance;
        private String currency;
    }
}

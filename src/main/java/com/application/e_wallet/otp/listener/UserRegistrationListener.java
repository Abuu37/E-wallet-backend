package com.application.e_wallet.otp.listener;

import com.application.e_wallet.common.event.UserRegisteredEvent;
import com.application.e_wallet.otp.service.OtpService;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class UserRegistrationListener {

    private final UserRepository userRepository;
    private final OtpService otpService;

    //======== React to a new registration, once its transaction has committed ========
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(UserRegisteredEvent event) {

        UserEntity user = userRepository.findById(event.userId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Registered user not found: " + event.userId()
                        ));
        otpService.sendVerificationOtp(user);
    }
}

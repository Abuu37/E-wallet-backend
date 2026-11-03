package com.application.e_wallet.common.annotion;


import com.application.e_wallet.common.validation.PinMatchesValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PinMatchesValidator.class)
@Documented
public @interface PinMatches {

    String message() default "PINs do not match";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

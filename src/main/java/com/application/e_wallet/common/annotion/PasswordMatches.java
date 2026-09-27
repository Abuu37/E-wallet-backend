package com.application.e_wallet.common.annotion;

import com.application.e_wallet.common.validation.PasswordMatchesValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PasswordMatchesValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordMatches {
    String message() default "Password and confirm password must match";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

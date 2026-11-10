package com.application.e_wallet.security;

import com.application.e_wallet.security.jwt.JwtAccessDeniedHandler;
import com.application.e_wallet.security.jwt.JwtAuthenticationEntryPoint;
import com.application.e_wallet.security.jwt.JwtAuthenticationFilter;
import com.application.e_wallet.security.jwt.PinChangeRequiredFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final PinChangeRequiredFilter pinChangeRequiredFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    @Bean
    public PasswordEncoder pinEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                        )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/error",
                                "/api/v1/auth/customer-registration",
                                "/api/v1/auth/set-pin",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/forgot-pin",
                                "/api/v1/auth/reset-pin",
                                "/api/v1/otp/verify",
                                "/api/v1/otp/resend",
                                "/api/v1/locations/regions/",
                                "/api/v1/locations/districts/"
                        ).permitAll()
                        // Any new endpoint added under /api/v1/auth/** or /api/v1/otp/**
                        // is protected by default — add it above explicitly only if it
                        // must be public.
                        .anyRequest().authenticated()
                        )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(pinChangeRequiredFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

}

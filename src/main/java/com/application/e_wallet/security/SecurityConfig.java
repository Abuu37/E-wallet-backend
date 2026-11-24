package com.application.e_wallet.security;

import com.application.e_wallet.security.jwt.JwtAccessDeniedHandler;
import com.application.e_wallet.security.jwt.JwtAuthenticationEntryPoint;
import com.application.e_wallet.security.jwt.JwtAuthenticationFilter;
import com.application.e_wallet.security.jwt.PinChangeRequiredFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** Only these endpoints authenticate with a cookie, so only they need CSRF protection. */
    static final String REFRESH_PATH = "/api/v1/auth/refresh";
    static final String LOGOUT_PATH = "/api/v1/auth/logout";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final PinChangeRequiredFilter pinChangeRequiredFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    @Value("${app.auth.refresh-cookie.secure:true}")
    private boolean secureCookies;

    @Bean
    public PasswordEncoder pinEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Strict").secure(secureCookies));

        RequestMatcher cookieEndpoints = request ->
                HttpMethod.POST.matches(request.getMethod())
                        && (REFRESH_PATH.equals(request.getRequestURI()) || LOGOUT_PATH.equals(request.getRequestURI()));

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(cookieEndpoints))
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        // JSON API: nothing it returns should ever be rendered, framed or scripted.
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN)))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                        )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/error",
                                "/api/v1/customers/register",
                                "/api/v1/auth/set-pin",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/forgot-pin",
                                "/api/v1/auth/reset-pin",
                                "/api/v1/otp/verify",
                                "/api/v1/otp/resend",
                                "/api/v1/locations/regions/",
                                "/api/v1/locations/districts/",
                                "/api/v1/payments/stripe/webhook"
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
                .addFilterAfter(pinChangeRequiredFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);
        return http.build();
    }

}

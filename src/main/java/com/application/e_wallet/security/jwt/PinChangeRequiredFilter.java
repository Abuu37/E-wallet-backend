package com.application.e_wallet.security.jwt;

import com.application.e_wallet.common.exception.ErrorResponse;
import com.application.e_wallet.user.entity.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

// Runs after JwtAuthenticationFilter. An account (e.g. an agent issued a
// one-time PIN at onboarding) in PIN_CHANGE_REQUIRED can authenticate, but
// must not reach any endpoint other than the ones that let them change
// their PIN or sign out, until they do.
@Component
@RequiredArgsConstructor
public class PinChangeRequiredFilter extends OncePerRequestFilter {

    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/api/v1/auth/change-pin",
            "/api/v1/auth/logout",
            "/api/v1/auth/status"
    );

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser principal
                && UserStatus.PIN_CHANGE_REQUIRED.name().equals(principal.status())
                && !EXEMPT_PATHS.contains(request.getRequestURI())) {

            ErrorResponse errorResponse = ErrorResponse.builder()
                    .timestamp(Instant.now())
                    .status(HttpStatus.FORBIDDEN.value())
                    .error(HttpStatus.FORBIDDEN.getReasonPhrase())
                    .message("You must change your PIN before accessing this resource.")
                    .path(request.getRequestURI())
                    .build();

            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json");
            response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
            return;
        }

        filterChain.doFilter(request, response);
    }
}

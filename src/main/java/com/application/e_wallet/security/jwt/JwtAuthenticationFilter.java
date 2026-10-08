package com.application.e_wallet.security.jwt;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

// Intercepts the request, extracts the token from the header, asks the
// validation tool to check it, and logs the user into Spring Security.
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 1. The FILTER intercepts the request and grabs the header
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String jwt = authHeader.substring(7);

            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    // 2. The FILTER parses and validates the token — parseToken()
                    //    throws if it's expired, malformed, or signed with the wrong key
                    String tokenType = jwtService.extractTokenType(jwt);

                    // Only an access token may authenticate a request — a refresh
                    // token is only meant to be exchanged for a new access token,
                    // never used to access protected endpoints directly.
                    if (JwtService.ACCESS_TOKEN_TYPE.equals(tokenType)) {
                        UUID userId = jwtService.extractUserId(jwt);
                        String email = jwtService.extractEmail(jwt);
                        String rolesClaim = jwtService.extractRoles(jwt);
                        List<String> permissionsClaim = jwtService.extractPermissions(jwt);

                        List<GrantedAuthority> authorities = Stream.concat(
                                splitClaim(rolesClaim).map(role -> "ROLE_" + role),
                                permissionsClaim.stream()
                        ).<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();

                        // 3. If validation passes, the FILTER logs the user into Spring Security
                        AuthenticatedUser principal = new AuthenticatedUser(userId, email);

                        UsernamePasswordAuthenticationToken authToken =
                                new UsernamePasswordAuthenticationToken(principal, null, authorities);

                        SecurityContextHolder.getContext().setAuthentication(authToken);
                    } else {
                        log.debug("Rejected token with unexpected type: {}", tokenType);
                    }

                } catch (JwtException | IllegalArgumentException exception) {
                    log.debug("Rejected invalid JWT: {}", exception.getMessage());
                    SecurityContextHolder.clearContext();
                }
            }
        }

        // 4. Let the request continue to the Controller
        filterChain.doFilter(request, response);
    }

    private Stream<String> splitClaim(String claim) {
        return (claim == null || claim.isBlank())
                ? Stream.empty()
                : Arrays.stream(claim.split(","));
    }

}

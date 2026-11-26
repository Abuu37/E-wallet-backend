package com.application.e_wallet.wallet.controller;

import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.wallet.dto.BeneficiaryItem;
import com.application.e_wallet.wallet.service.BeneficiaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/beneficiaries")
@RequiredArgsConstructor
public class BeneficiaryController {

    private final BeneficiaryService beneficiaryService;

    /** People this customer has sent money to, most recent first. */
    @GetMapping
    @PreAuthorize("hasAuthority('WALLET_TO_WALLET_TRANSFER')")
    public ResponseEntity<List<BeneficiaryItem>> recent(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        return ResponseEntity.ok(beneficiaryService.recent(principal.userId()));
    }
}

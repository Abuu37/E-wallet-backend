package com.application.e_wallet.tariff.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.tariff.dto.TariffBandItem;
import com.application.e_wallet.tariff.service.TariffService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tariffs")
@RequiredArgsConstructor
public class TariffController {

    private final TariffService tariffService;

    /** Any signed-in person: the confirm screens show the fee before the PIN is typed. */
    @GetMapping("/quote")
    @PreAuthorize("isAuthenticated()")
    public org.springframework.http.ResponseEntity<com.application.e_wallet.tariff.dto.QuoteResponse> quote(
            @org.springframework.web.bind.annotation.RequestParam String type,
            @org.springframework.web.bind.annotation.RequestParam java.math.BigDecimal amount) {
        return org.springframework.http.ResponseEntity.ok(tariffService.quote(type, amount));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CHARGE_VIEW')")
    public ResponseEntity<ApiResponse<List<TariffBandItem>>> list() {
        return ResponseEntity.ok(ApiResponse.<List<TariffBandItem>>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Tariffs retrieved successfully")
                .data(tariffService.listBands())
                .build());
    }
}

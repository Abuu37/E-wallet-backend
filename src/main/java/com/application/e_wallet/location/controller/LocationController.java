package com.application.e_wallet.location.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.location.dto.DistrictResponse;
import com.application.e_wallet.location.dto.RegionResponse;
import com.application.e_wallet.location.service.LocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @GetMapping("/regions")
    public ResponseEntity<ApiResponse<List<RegionResponse>>> listRegions() {

        List<RegionResponse> regions = locationService.listRegions();

        ApiResponse<List<RegionResponse>> body = ApiResponse.<List<RegionResponse>>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Regions retrieved successfully.")
                .data(regions)
                .build();

        return ResponseEntity.ok(body);
    }

    @GetMapping("/districts")
    public ResponseEntity<ApiResponse<List<DistrictResponse>>> listDistricts(
            @RequestParam String regionId
    ) {
        List<DistrictResponse> districts = locationService.listDistricts(regionId);

        ApiResponse<List<DistrictResponse>> body = ApiResponse.<List<DistrictResponse>>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Districts retrieved successfully.")
                .data(districts)
                .build();

        return ResponseEntity.ok(body);
    }
}

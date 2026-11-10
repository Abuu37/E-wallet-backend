package com.application.e_wallet.location.service;

import com.application.e_wallet.location.dto.DistrictResponse;
import com.application.e_wallet.location.dto.RegionResponse;
import com.application.e_wallet.location.repository.DistrictRepository;
import com.application.e_wallet.location.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LocationService {

    private final RegionRepository regionRepository;
    private final DistrictRepository districtRepository;

    public List<RegionResponse> listRegions() {
        return regionRepository.findAllByOrderByNameAsc().stream()
                .map(region -> new RegionResponse(region.getExternalId(), region.getName()))
                .toList();
    }

    public List<DistrictResponse> listDistricts(String regionExternalId) {
        return districtRepository.findAllByRegion_ExternalIdOrderByNameAsc(regionExternalId).stream()
                .map(district -> new DistrictResponse(
                        district.getExternalId(),
                        district.getName(),
                        regionExternalId
                ))
                .toList();
    }
}

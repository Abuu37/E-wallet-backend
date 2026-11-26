package com.application.e_wallet.agent.dto;

/** One card on "Find an agent". Hours are not recorded yet, so only open or closed is shown. */
public record NearbyAgentResponse(String name, String tillNumber, String district, String region, boolean open) {
}

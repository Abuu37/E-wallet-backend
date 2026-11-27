package com.application.e_wallet.staff.dto;

/** Who the signed-in staff member is, for the top bar of the console. */
public record StaffProfileResponse(String fullName, String firstName, String jobTitle) {
}

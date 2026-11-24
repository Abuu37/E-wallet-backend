package com.application.e_wallet.auth.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@JsonPropertyOrder({
        "accessToken",
        "accessTokenExpiresIn",
        "refreshTokenExpiresIn",
        "tokenType",
        "roles",
        "permissions",
        "mustChangePin"
})
public class LoginResponse {

    private String accessToken;
    private long accessTokenExpiresIn;
    private long refreshTokenExpiresIn;
    private String tokenType;
    private List<String> roles;
    private List<String> permissions;
    private boolean mustChangePin;

}

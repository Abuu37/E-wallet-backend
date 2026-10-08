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
        "refreshToken",
        "refreshTokenExpiresIn",
        "tokenType",
        "roles",
        "permissions"
})
public class LoginResponse {

    private String accessToken;
    private long accessTokenExpiresIn;
    private String refreshToken;
    private long refreshTokenExpiresIn;
    private String tokenType;
    private List<String> roles;
    private List<String> permissions;

}

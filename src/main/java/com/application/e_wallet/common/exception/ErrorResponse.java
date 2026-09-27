package com.application.e_wallet.common.exception;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@Builder
public class ErrorResponse {

    private Instant timestamp;
    private int status;
    private String error;
    private String message;
    private String path;

    private Map<String, String> errors;
}

package com.application.e_wallet.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private Instant timestamp;
    private int status;
    private String message;
    private T data;
}

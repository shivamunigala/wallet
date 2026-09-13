package com.shiva.wallet.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uniform error body. {@code code} is stable and machine-readable so the burst script can
 * assert on it without parsing prose.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final String code;
    private final String message;
    private final String correlationId;
    private final Object detail;

    public ErrorResponse(String code, String message, String correlationId, Object detail) {
        this.code = code;
        this.message = message;
        this.correlationId = correlationId;
        this.detail = detail;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Object getDetail() {
        return detail;
    }
}

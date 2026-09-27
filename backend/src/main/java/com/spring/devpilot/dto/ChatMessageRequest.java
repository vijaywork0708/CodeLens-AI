package com.spring.devpilot.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatMessageRequest(
        String content) {
}

package com.kbagent.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank(message = "question 不能为空") String question, Integer topK) {
}

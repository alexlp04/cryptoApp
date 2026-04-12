package com.bottrading.training.domain;

import java.util.Map;

public record OptimizationResult(
        Map<String, Object> resultData,
        boolean success,
        String errorMessage) {
}

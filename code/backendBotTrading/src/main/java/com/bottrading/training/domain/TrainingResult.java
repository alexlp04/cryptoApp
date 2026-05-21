package com.bottrading.training.domain;

import java.util.Map;

public record TrainingResult(
        String modelPath,
        Map<String, Object> metrics,
        Map<String, Object> tradingSimulation,
        boolean success,
        String errorMessage) {
}
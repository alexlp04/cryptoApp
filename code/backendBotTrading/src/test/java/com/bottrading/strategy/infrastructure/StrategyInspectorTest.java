package com.bottrading.strategy.infrastructure;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StrategyInspectorTest {

    @Test
    @DisplayName("Nombres con traversal o inexistentes se rechazan antes de lanzar Python (#117)")
    void invalid_names_are_rejected_before_running_anything() {
        assertThrows(IllegalArgumentException.class, () -> StrategyInspector.getWarmupPeriod("../x"));
        assertThrows(IllegalArgumentException.class, () -> StrategyInspector.getWarmupPeriod("..\\x"));
        assertThrows(IllegalArgumentException.class, () -> StrategyInspector.getWarmupPeriod("NoExiste"));
    }

    @Test
    @DisplayName("Un nombre con salto de línea e import malicioso no se ejecuta como código")
    void newline_injection_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StrategyInspector.getWarmupPeriod("a'\nimport os\nos.system('x')\n'"));
    }

    @Test
    @DisplayName("Nombre vacío o nulo se rechaza")
    void blank_name_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> StrategyInspector.getWarmupPeriod(" "));
        assertThrows(IllegalArgumentException.class, () -> StrategyInspector.getWarmupPeriod(null));
    }
}

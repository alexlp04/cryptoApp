package com.bottrading.strategy.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;

class StrategyCatalogFilesTest {

    private final StrategyCatalogService service =
            new StrategyCatalogService(org.mockito.Mockito.mock(com.bottrading.strategy.application.port.out.InstanciaEstrategiaRepositoryPort.class));

    @Test
    @DisplayName("El listado no incluye __init__ ni BaseStrategy y está ordenado (#119)")
    void listing_excludes_infrastructure_files_and_is_sorted() {
        List<String> ficheros = service.listarFicherosDeEstrategias();

        assertFalse(ficheros.contains("- __init__"));
        assertFalse(ficheros.contains("- BaseStrategy"));
        assertTrue(ficheros.contains("- StressTestStrategy"));
        assertTrue(ficheros.equals(ficheros.stream().sorted().toList()));
    }

    @Test
    @DisplayName("existeEstrategia rechaza traversal y nombres inexistentes (#119)")
    void exists_rejects_traversal() {
        assertTrue(service.existeEstrategia("StressTestStrategy"));
        assertFalse(service.existeEstrategia("../x"));
        assertFalse(service.existeEstrategia("..\\x"));
        assertFalse(service.existeEstrategia("NoExiste"));
    }
}

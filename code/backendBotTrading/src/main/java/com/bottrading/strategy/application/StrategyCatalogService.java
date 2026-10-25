package com.bottrading.strategy.application;

import java.io.File;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import com.bottrading.shared.utils.PathConfig;
import com.bottrading.strategy.application.port.in.StrategyCatalogUseCase;
import com.bottrading.strategy.application.port.out.InstanciaEstrategiaRepositoryPort;

import lombok.extern.slf4j.Slf4j;

/**
 * Servicio de catálogo de estrategias.
 * Gestiona: inventario de archivos, disponibilidad, capital comprometido.
 * 
 * Responsabilidad única: información sobre estrategias disponibles.
 */
@Slf4j
@Service
public class StrategyCatalogService implements StrategyCatalogUseCase {

    private final InstanciaEstrategiaRepositoryPort instanciaRepo;

    public StrategyCatalogService(InstanciaEstrategiaRepositoryPort instanciaRepo) {
        this.instanciaRepo = instanciaRepo;
    }

    /**
     * Lista los ficheros .py de estrategias disponibles en disco.
     */
    @Override
    public List<String> listarFicherosDeEstrategias() {
        File folder = new File(PathConfig.STRATEGIES_DIR);
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".py"));

        if (files == null || files.length == 0) {
            log.info("No se encontraron ficheros en: {}", PathConfig.STRATEGIES_DIR);
            return List.of(); // vacío, no un mensaje: un consumidor lo trataría como nombre de estrategia
        }

        // __init__ y BaseStrategy no son estrategias; solo se quita la extensión final; orden estable
        return Arrays.stream(files)
                .map(File::getName)
                .filter(n -> !n.equals("__init__.py") && !n.equals("BaseStrategy.py"))
                .map(n -> "- " + n.substring(0, n.length() - ".py".length()))
                .sorted()
                .toList();
    }

    /**
     * Obtiene el capital total comprometido (en reserva) en una billetera.
     */
    @Override
    public BigDecimal getCapitalComprometido(Long walletAsociada) {
        BigDecimal sum = instanciaRepo.sumCapitalActivoByWallet(walletAsociada);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /**
     * Obtiene el capital disponible en una billetera.
     * (Se calcula restando capital comprometido del saldo total)
     */
    @Override
    public BigDecimal getCapitalDisponible(Long walletAsociada, BigDecimal saldoTotal) {
        BigDecimal comprometido = getCapitalComprometido(walletAsociada);
        return saldoTotal.subtract(comprometido);
    }

    /**
     * Verifica si una estrategia .py existe en disco.
     */
    @Override
    public boolean existeEstrategia(String nombreArchivo) {
        try {
            PathConfig.getValidStrategyPath(nombreArchivo); // rechaza '..', separadores y nombres inexistentes
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Obtiene la ruta validada de una estrategia.
     */
    @Override
    public String getValidStrategyPath(String nombreEstra) throws IllegalArgumentException {
        if (!existeEstrategia(nombreEstra)) {
            throw new IllegalArgumentException("Estrategia no encontrada: " + nombreEstra);
        }
        return PathConfig.getValidStrategyPath(nombreEstra);
    }
}

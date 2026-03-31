package com.bottrading.application.strategy;

import com.bottrading.domain.strategy.InstanciaEstrategiaRepository;
import com.bottrading.utils.PathConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Servicio de catálogo de estrategias.
 * Gestiona: inventario de archivos, disponibilidad, capital comprometido.
 * 
 * Responsabilidad única: información sobre estrategias disponibles.
 */
@Slf4j
@Service
public class StrategyCatalogService {

    private final InstanciaEstrategiaRepository instanciaRepo;

    public StrategyCatalogService(InstanciaEstrategiaRepository instanciaRepo) {
        this.instanciaRepo = instanciaRepo;
    }

    /**
     * Lista los ficheros .py de estrategias disponibles en disco.
     */
    public List<String> listarFicherosDeEstrategias() {
        File folder = new File(PathConfig.STRATEGIES_DIR);
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".py"));

        if (files == null || files.length == 0) {
            log.info("No se encontraron ficheros en: {}", PathConfig.STRATEGIES_DIR);
            return List.of("No hay estrategias disponibles en: " + PathConfig.STRATEGIES_DIR);
        }

        return Arrays.stream(files)
                .map(f -> "- " + f.getName().replace(".py", ""))
                .toList();
    }

    /**
     * Obtiene el capital total comprometido (en reserva) en una billetera.
     */
    public BigDecimal getCapitalComprometido(Long walletAsociada) {
        BigDecimal sum = instanciaRepo.sumCapitalActivoByWallet(walletAsociada);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /**
     * Obtiene el capital disponible en una billetera.
     * (Se calcula restando capital comprometido del saldo total)
     */
    public BigDecimal getCapitalDisponible(Long walletAsociada, BigDecimal saldoTotal) {
        BigDecimal comprometido = getCapitalComprometido(walletAsociada);
        return saldoTotal.subtract(comprometido);
    }

    /**
     * Verifica si una estrategia .py existe en disco.
     */
    public boolean existeEstrategia(String nombreArchivo) {
        String nombreConExtension = nombreArchivo.endsWith(".py") ? nombreArchivo : nombreArchivo + ".py";
        File archivo = new File(PathConfig.STRATEGIES_DIR, nombreConExtension);
        return archivo.exists();
    }

    /**
     * Obtiene la ruta validada de una estrategia.
     */
    public String getValidStrategyPath(String nombreEstra) throws IllegalArgumentException {
        if (!existeEstrategia(nombreEstra)) {
            throw new IllegalArgumentException("Estrategia no encontrada: " + nombreEstra);
        }
        return PathConfig.getValidStrategyPath(nombreEstra);
    }
}

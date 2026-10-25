package com.bottrading.backtesting.application;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bottrading.backtesting.application.port.in.ExecuteBacktestUseCase;
import com.bottrading.backtesting.application.port.out.BacktestPersistencePort;
import com.bottrading.backtesting.infrastructure.StatsCsvRepository;
import com.bottrading.market.domain.Vela;
import com.bottrading.market.domain.VelaRepository;
import com.bottrading.shared.exceptions.DataFetchException;
import com.bottrading.shared.exceptions.StrategyExecutionException;
import com.bottrading.shared.utils.ConsoleLoader;
import com.bottrading.shared.utils.PathConfig;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeRequest;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessagePackCodec;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessageType;
import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class BacktestingService implements ExecuteBacktestUseCase {

    private final Gson gson = new Gson();
    private final VelaRepository velaRepository;
    private final BacktestPersistencePort backtestPersistencePort;
    private final StatsCsvRepository statsCsvRepository;
    private final PythonBridgeFacade pythonBridgeFacade;

    public BacktestingService(VelaRepository velaRepository,
            BacktestPersistencePort backtestPersistencePort,
            StatsCsvRepository statsCsvRepository,
            PythonBridgeFacade pythonBridgeFacade) {
        this.velaRepository = velaRepository;
        this.backtestPersistencePort = backtestPersistencePort;
        this.statsCsvRepository = statsCsvRepository;
        this.pythonBridgeFacade = pythonBridgeFacade;
    }

    @Override
    @SuppressWarnings("java:S107")
    public void ejecutarBacktest(String nombreEstra, String modelName, String tf, List<String> coins,
            BigDecimal capitalAsignado, BigDecimal risk, boolean limpiarBacktestsPrevios,
            boolean guardarTrades) {

        // Primero todo lo que puede fallar sin efectos: nombre de estrategia y datos.
        // Solo después se borran los resultados previos (si no, un typo o la falta de datos los perdía).
        String strategyPath = PathConfig.getValidStrategyPath(nombreEstra);

        Map<String, List<Vela>> velasPorSimbolo;
        ConsoleLoader.getInstance().startDots("Cargando datos históricos del portfolio...");
        try {
            velasPorSimbolo = cargarDatosHistoricos(coins, tf);
        } finally {
            ConsoleLoader.getInstance().stopClear();
        }

        if (velasPorSimbolo.isEmpty()) {
            throw new DataFetchException("No hay datos históricos para " + coins + " en " + tf
                    + ". Ejecuta 'fetch' antes del backtest.");
        }

        if (limpiarBacktestsPrevios) {
            backtestPersistencePort.limpiarResultadosPrevios(nombreEstra);
        }

        String jsonResultado = ejecutarMotorBacktest(strategyPath, nombreEstra, modelName, tf, velasPorSimbolo,
                capitalAsignado, risk, guardarTrades);

        if (jsonResultado != null && !jsonResultado.isEmpty()) {
            statsCsvRepository.guardarEstadisticasDelBacktest(nombreEstra, tf, modelName, jsonResultado);
            ConsoleLoader.getInstance().stopClear();
            log.info("Resultados guardados en: {}{}{}", PathConfig.RESULTS_DIR, File.separator, nombreEstra);
        } else {
            throw new StrategyExecutionException("El motor de backtesting no retornó resultados.");
        }
    }

    private Map<String, List<Vela>> cargarDatosHistoricos(List<String> coins, String tf) {
        // LinkedHashMap: conserva el orden de -coins (logs y resultados deterministas)
        Map<String, List<Vela>> velasPorSimbolo = new LinkedHashMap<>();

        for (String symbol : coins) {
            List<Vela> velas = velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, tf);

            if (velas.isEmpty()) {
                log.warn("Aviso: No se encontraron velas para {} / {}", symbol, tf);
            } else {
                velasPorSimbolo.put(symbol, velas);
                log.debug("Cargadas {} velas para {}/{}", velas.size(), symbol, tf);
            }
        }

        return velasPorSimbolo;
    }

    @SuppressWarnings("java:S107")
    private String ejecutarMotorBacktest(String rutaEstrategia, String nombreEstrategia, String modelName,
            String timeframe, Map<String, List<Vela>> velasPorSimbolo, BigDecimal capitalAsignado,
            BigDecimal risk, boolean guardarTrades)
            throws StrategyExecutionException {
        ConsoleLoader.getInstance().startDots("Serializando velas para el motor de backtest...");
        Map<String, List<Map<String, Object>>> velasMapeadas = transformarVelasParaPython(velasPorSimbolo);
        ConsoleLoader.getInstance().stopClear();

        Map<String, Object> payload = construirPayload(rutaEstrategia, timeframe, velasMapeadas, capitalAsignado, risk,
                nombreEstrategia, modelName, guardarTrades);

        return invocarMotorPythonConRetries(payload);
    }

    private Map<String, List<Map<String, Object>>> transformarVelasParaPython(Map<String, List<Vela>> velasPorSimbolo) {
        Map<String, List<Map<String, Object>>> resultado = new LinkedHashMap<>();

        velasPorSimbolo.forEach((symbol, listaVelas) -> {
            List<Map<String, Object>> listaTransformada = listaVelas.stream()
                    .map(this::convertirVelaAMapa) // Delegamos la conversión de 1 vela
                    .toList();
            resultado.put(symbol, listaTransformada);
        });

        return resultado;
    }

    Map<String, Object> convertirVelaAMapa(Vela v) {
        // double y no BigDecimal: msgpack-jackson rechaza los BigDecimal que no caben exactos en un
        // double (p. ej. 1234567890.12345678) y Python usa float64 de todos modos.
        Map<String, Object> m = new HashMap<>();
        m.put("timestamp", v.getOpenTime());
        m.put("open", v.getOpen().doubleValue());
        m.put("high", v.getHigh().doubleValue());
        m.put("low", v.getLow().doubleValue());
        m.put("close", v.getClose().doubleValue());
        m.put("volume", v.getVolume().doubleValue());
        return m;
    }

    @SuppressWarnings("java:S107")
    private Map<String, Object> construirPayload(String ruta, String tf, Map<String, List<Map<String, Object>>> velas,
            BigDecimal capitalAsignado, BigDecimal risk, String nombreEstrategia, String modelName,
            boolean guardarTrades) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("strategy_path", ruta);
        payload.put("strategy_name", nombreEstrategia);
        payload.put("timeframe", tf);
        payload.put("velas", velas);
        payload.put("capital", capitalAsignado.doubleValue());
        payload.put("risk_per_trade", risk.doubleValue());
        payload.put("escribir_trades", guardarTrades);
        if (modelName != null && !modelName.isBlank()) {
            payload.put("model_name", modelName);
        }
        return payload;
    }

    /**
     * Invoca el motor Python con timeout y destrucción de proceso en caso de error.
     * Soporte para MessagePack (próxima fase) manteniendo compatibilidad JSON.
     */
    private String invocarMotorPythonConRetries(Map<String, Object> payload) throws StrategyExecutionException {
        try {
            long startTime = System.currentTimeMillis();
            log.debug("Iniciando backtest para {} símbolo(s)", ((Map<?, ?>) payload.getOrDefault("velas", Map.of())).size());

            ConsoleLoader.getInstance().startSpinner("Ejecutando backtest, por favor espera...");
            PythonBridgeRequest<String> request = PythonBridgeRequest.<String>builder(PathConfig.ENGINE_BACKTEST_PATH)
                    .operationName("backtest")
                    .noTimeout()
                    // Un backtest fallido es determinista (estrategia, modelo, datos): reintentarlo solo
                    // repite el trabajo y duplicaría los trades que el motor añade al CSV.
                    .maxRetries(1)
                    .stdinWriter(os -> escribirEnvelopeBacktest(os, payload))
                    .stdoutReader(this::parseResponseWithFallback)
                    .onStderrLine(line -> log.info("PY [backtest]: {}", line))
                    .build();

            String stdout = pythonBridgeFacade.execute(request);

            ConsoleLoader.getInstance().stopClear();
            long duration = System.currentTimeMillis() - startTime;
            log.info("Backtest completado en {} ms", duration);
            return stdout;
        } catch (PythonBridgeExecutionException e) {
            ConsoleLoader.getInstance().stopClear();
            throw new StrategyExecutionException("Backtest falló: " + e.getMessage(), e);
        } catch (StrategyExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error inesperado en backtest: {}", e.getMessage(), e);
            ConsoleLoader.getInstance().stopClear();
            throw new StrategyExecutionException("Error inesperado: " + e.getMessage(), e);
        }
    }

    /**
     * Lee un flujo de entrada completo y lo convierte a String.
     */
    private void escribirEnvelopeBacktest(java.io.OutputStream outputStream, Map<String, Object> payload)
            throws IOException {
        IpcMessagePackCodec.writeEnvelope(outputStream, IpcMessageType.BACKTEST_REQUEST, payload);
    }

    private String leerEnvelopeBacktest(InputStream inputStream) throws IOException {
        Map<String, Object> envelope = IpcMessagePackCodec.readEnvelope(inputStream);
        Object payload = envelope.get("payload");
        return gson.toJson(payload == null ? Map.of() : payload);
    }

    private String parseResponseWithFallback(InputStream inputStream) throws IOException {
        return leerEnvelopeBacktest(inputStream);
    }
}
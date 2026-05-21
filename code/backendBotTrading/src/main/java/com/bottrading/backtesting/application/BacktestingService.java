package com.bottrading.backtesting.application;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bottrading.backtesting.application.port.in.ExecuteBacktestUseCase;
import com.bottrading.backtesting.application.port.out.BacktestPersistencePort;
import com.bottrading.backtesting.infrastructure.StatsCsvRepository;
import com.bottrading.config.ProcessExecutorConfig;
import com.bottrading.market.domain.Vela;
import com.bottrading.market.domain.VelaRepository;
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

        if (limpiarBacktestsPrevios) {
            backtestPersistencePort.limpiarResultadosPrevios(nombreEstra);
        }

        ConsoleLoader.getInstance().startDots("Cargando datos históricos del portfolio...");
        Map<String, List<Vela>> velasPorSimbolo = cargarDatosHistoricos(coins, tf);

        if (velasPorSimbolo.isEmpty()) {
            log.error("Abortado: No hay datos históricos para procesar.");
            return;
        }

        String strategyPath = PathConfig.getValidStrategyPath(nombreEstra);
        ConsoleLoader.getInstance().stopClear();

        String jsonResultado = ejecutarMotorBacktest(strategyPath, nombreEstra, modelName, tf, velasPorSimbolo,
                capitalAsignado, risk, guardarTrades);

        if (jsonResultado != null && !jsonResultado.isEmpty()) {
            statsCsvRepository.guardarEstadisticasDelBacktest(nombreEstra, tf, modelName, jsonResultado);
            ConsoleLoader.getInstance().stopClear();
            log.info("Resultados guardados en: {}{}{}", PathConfig.RESULTS_DIR, File.separator, nombreEstra);
        } else {
            log.error("El motor de backtesting no retornó resultados.");
        }
    }

    private Map<String, List<Vela>> cargarDatosHistoricos(List<String> coins, String tf) {
        Map<String, List<Vela>> velasPorSimbolo = new HashMap<>();

        for (String symbol : coins) {
            List<Vela> velas = velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, tf);
            velasPorSimbolo.put(symbol, velas);

            if (velas.isEmpty()) {
                log.warn("Aviso: No se encontraron velas para {} / {}", symbol, tf);
            } else {
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
        Map<String, List<Map<String, Object>>> resultado = new HashMap<>();

        velasPorSimbolo.forEach((symbol, listaVelas) -> {
            List<Map<String, Object>> listaTransformada = listaVelas.stream()
                    .map(this::convertirVelaAMapa) // Delegamos la conversión de 1 vela
                    .toList();
            resultado.put(symbol, listaTransformada);
        });

        return resultado;
    }

    private Map<String, Object> convertirVelaAMapa(Vela v) {
        Map<String, Object> m = new HashMap<>();
        m.put("timestamp", v.getOpenTime());
        m.put("open", v.getOpen());
        m.put("high", v.getHigh());
        m.put("low", v.getLow());
        m.put("close", v.getClose());
        m.put("volume", v.getVolume());
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
        payload.put("capital", capitalAsignado);
        payload.put("risk_per_trade", risk);
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
                    .maxRetries(ProcessExecutorConfig.MAX_RETRIES)
                    .retryDelayMs(ProcessExecutorConfig.RETRY_DELAY_MS)
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
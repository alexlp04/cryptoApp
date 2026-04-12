package com.bottrading.market.application;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.bottrading.market.application.port.in.CalculateIndicatorsUseCase;
import com.bottrading.market.domain.IndicadorTecnicoDTO;
import com.bottrading.market.domain.Vela;
import com.bottrading.market.domain.VelaDTO;
import com.bottrading.shared.exceptions.PythonProcessException;
import com.bottrading.shared.utils.PathConfig;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeRequest;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessagePackCodec;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessageType;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Servicio encargado del cálculo de indicadores técnicos sobre los datos de
 * mercado.
 * Delega el procesamiento matemático a un script de Python y persiste los
 * resultados
 * utilizando inserción masiva (Batch Insert) para máximo rendimiento.
 */
@Slf4j
@Service
public class IndicatorsService implements CalculateIndicatorsUseCase {

    private static final Type INDICADOR_DTO_LIST_TYPE = new TypeToken<List<IndicadorTecnicoDTO>>() {}.getType();

    private final Gson gson = new Gson();

    private final JdbcTemplate jdbcTemplate;
    private final PythonBridgeFacade pythonBridgeFacade;

    private static final int BATCH_SIZE = 100000;
    private static final int OVERLAP = 50;
    private static final int MAX_RETRIES = 3;
    private static final int JDBC_INSERT_BATCH_SIZE = 5000;

    public IndicatorsService(JdbcTemplate jdbcTemplate, PythonBridgeFacade pythonBridgeFacade) {
        this.jdbcTemplate = jdbcTemplate;
        this.pythonBridgeFacade = pythonBridgeFacade;
    }

    /**
     * Calcula indicadores técnicos básicos procesando las velas por lotes.
     */
    @Override
    public void calculateBasicIndicators(String symbol, List<Vela> todasLasVelas, boolean guardarPrimeras50) {
        if (todasLasVelas == null || todasLasVelas.isEmpty()) {
            log.warn("La lista de velas está vacía. Abortando cálculo para {}", symbol);
            return;
        }

        log.info("Iniciando cálculo de indicadores por lotes. Total de velas: {}", todasLasVelas.size());
        int totalProcesadas = 0;

        for (int i = 0; i < todasLasVelas.size(); i += BATCH_SIZE) {
            int start = Math.max(0, i - OVERLAP);
            int end = Math.min(todasLasVelas.size(), i + BATCH_SIZE);
            List<Vela> loteVelas = todasLasVelas.subList(start, end);

            int numLote = (i / BATCH_SIZE) + 1;

            boolean guardarTodoEsteLote = (i == 0) && guardarPrimeras50;

            try {
                int procesadasEnLote = procesarLote(loteVelas, guardarTodoEsteLote);
                totalProcesadas += procesadasEnLote;

                if (procesadasEnLote > 0) {
                    log.info("Lote {} completado. Indicadores guardados: {}", numLote, totalProcesadas);
                }

            } catch (Exception e) {
                log.error("Error procesando lote {}: {}", numLote, e.getMessage(), e);
            }
        }

        log.info("Cálculo de indicadores finalizado para {}. Total guardados en BD: {}", symbol, totalProcesadas);
    }

    /**
     * Orquesta el envío y recepción de un único lote de datos hacia Python.
     * Implementa timeouts, retries automáticos y streaming con MessagePack.
     * 
     * @return El número de indicadores guardados en este lote.
     */
    private int procesarLote(List<Vela> loteVelas, boolean esPrimerLote) throws PythonProcessException {
        try {
            return ejecutarIntentoLote(loteVelas, esPrimerLote);
        } catch (PythonBridgeExecutionException e) {
            throw new PythonProcessException("Error ejecutando proceso de indicadores: " + e.getMessage(), e);
        }
    }

        private int ejecutarIntentoLote(List<Vela> loteVelas, boolean esPrimerLote)
            throws PythonBridgeExecutionException {
        List<VelaDTO> velasDTO = loteVelas.stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());

        try {
            PythonBridgeRequest<Integer> request = PythonBridgeRequest.<Integer>builder(PathConfig.INDICATORS_PATH)
                    .operationName("calculate-indicators")
                    .noTimeout()
                    .maxRetries(MAX_RETRIES)
                    .retryDelayMs(1000L)
                    .stdinWriter(os -> IpcMessagePackCodec.writeEnvelope(
                            os,
                            IpcMessageType.INDICATORS_REQUEST,
                            Map.of("velas", velasDTO)))
                    .stdoutReader(in -> leerResultadosDePythonYGuardar(in, loteVelas, esPrimerLote))
                    .onStderrLine(line -> log.info("PY [indicators]: {}", line))
                    .build();

            return pythonBridgeFacade.execute(request);
        } finally {
            velasDTO.clear();
        }
    }

    private int leerResultadosDePythonYGuardar(InputStream in, List<Vela> loteVelas, boolean esPrimerLote)
            throws PythonProcessException, IOException {
        List<IndicadorTecnicoDTO> dtos = leerIndicadoresDesdeRespuestaIpc(in);

        if (dtos == null || dtos.isEmpty()) {
            return 0;
        }

        Set<Long> idsValidos = obtenerIdsValidosDelLote(loteVelas, esPrimerLote);

        List<IndicadorTecnicoDTO> resultadosFiltrados = dtos.stream()
                .filter(dto -> dto.getId() != null)
                .filter(dto -> idsValidos.contains(dto.getId()))
                .toList();

        if (!resultadosFiltrados.isEmpty()) {
            guardarIndicadoresMasivo(resultadosFiltrados);
            log.debug("Guardados {} indicadores del lote", resultadosFiltrados.size());
        }
        return resultadosFiltrados.size();
    }

    private List<IndicadorTecnicoDTO> leerIndicadoresDesdeRespuestaIpc(InputStream inputStream) throws IOException {
        Map<String, Object> envelope = IpcMessagePackCodec.readEnvelope(inputStream);
        Object payloadObj = envelope.get("payload");
        if (payloadObj instanceof Map<?, ?> payloadMap) {
            Object indicadoresObj = payloadMap.get("indicadores");
            if (indicadoresObj != null) {
                String indicadoresJson = gson.toJson(indicadoresObj);
                List<IndicadorTecnicoDTO> parsed = gson.fromJson(indicadoresJson, INDICADOR_DTO_LIST_TYPE);
                return parsed != null ? parsed : List.of();
            }
        }
        return List.of();
    }

    /**
     * Inserción en base de datos de alta velocidad saltándose la caché de
     * Hibernate.
     */
    private void guardarIndicadoresMasivo(List<IndicadorTecnicoDTO> indicadores) {
        if (indicadores.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO indicador_tecnico (vela_id, tipo, parametros, valor, fecha_creacion, eliminado) " +
            "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, FALSE)";

        int total = indicadores.size();
        int guardados = 0;

        for (int start = 0; start < total; start += JDBC_INSERT_BATCH_SIZE) {
            int end = Math.min(total, start + JDBC_INSERT_BATCH_SIZE);
            List<IndicadorTecnicoDTO> chunk = indicadores.subList(start, end);

            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(@org.springframework.lang.NonNull PreparedStatement ps, int i) throws SQLException {
                    IndicadorTecnicoDTO dto = chunk.get(i);

                    ps.setLong(1, dto.getId());
                    ps.setString(2, dto.getTipo());
                    ps.setString(3, dto.getParametros());
                    ps.setBigDecimal(4, dto.getValor());
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });

            guardados += chunk.size();
            if (guardados % 25000 == 0 || guardados == total) {
                log.info("Persistencia de indicadores: {}/{}", guardados, total);
            }
        }
    }

    /**
     * Excluye los IDs de las velas de solapamiento para no guardar indicadores
     * duplicados.
     */
    private Set<Long> obtenerIdsValidosDelLote(List<Vela> loteVelas, boolean esPrimerLote) {
        int inicioReal = esPrimerLote ? 0 : OVERLAP;
        return loteVelas.subList(inicioReal, loteVelas.size()).stream()
                .map(Vela::getId)
                .collect(Collectors.toSet());
    }

    private VelaDTO mapToDTO(Vela v) {
        VelaDTO dto = new VelaDTO();
        dto.setId(v.getId());
        dto.setSymbol(v.getSymbol());
        dto.setTimeInterval(v.getInterval());
        dto.setOpenTime(v.getOpenTime());
        dto.setCloseTime(v.getCloseTime());

        dto.setOpen(v.getOpen() != null ? v.getOpen().toString() : null);
        dto.setHigh(v.getHigh() != null ? v.getHigh().toString() : null);
        dto.setLow(v.getLow() != null ? v.getLow().toString() : null);
        dto.setClose(v.getClose() != null ? v.getClose().toString() : null);
        dto.setVolume(v.getVolume() != null ? v.getVolume().toString() : null);
        dto.setQuoteVolume(v.getQuoteVolume() != null ? v.getQuoteVolume().toString() : null);
        dto.setTakerBaseVolume(v.getTakerBaseVolume() != null ? v.getTakerBaseVolume().toString() : null);
        dto.setTakerQuoteVolume(v.getTakerQuoteVolume() != null ? v.getTakerQuoteVolume().toString() : null);
        dto.setTrades(v.getTrades());

        return dto;
    }

    @PreDestroy
    public void shutdown() {
        log.info("IndicatorsService shutdown completo");
    }
}
package com.bottrading.application.market;

import com.bottrading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.infrastructure.bridge.PythonBridgeRequest;
import com.bottrading.infrastructure.bridge.protocol.IpcMessagePackCodec;
import com.bottrading.infrastructure.bridge.protocol.IpcMessageType;
import com.bottrading.domain.market.Vela;
import com.bottrading.domain.market.VelaDTO;
import com.bottrading.domain.market.IndicadorTecnico;
import com.bottrading.domain.market.IndicadorTecnicoDTO;
import com.bottrading.domain.market.IndicadorRepository;
import com.bottrading.domain.market.VelaRepository;
import com.bottrading.exceptions.PythonProcessException;
import com.bottrading.utils.DataSerializationUtils;
import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Servicio encargado del cálculo de indicadores técnicos sobre los datos de
 * mercado.
 * Delega el procesamiento matemático a un script de Python y persiste los
 * resultados
 * utilizando inserción masiva (Batch Insert) para máximo rendimiento.
 */
@Slf4j
@Service
public class IndicatorsService {

    private static final Type INDICADOR_DTO_LIST_TYPE = new TypeToken<List<IndicadorTecnicoDTO>>() {}.getType();

    private final Gson gson = new Gson();

    private final JdbcTemplate jdbcTemplate;
    private final PythonBridgeFacade pythonBridgeFacade;

    private static final int BATCH_SIZE = 100000;
    private static final int OVERLAP = 50;
    private static final int MAX_RETRIES = 3;

    public IndicatorsService(JdbcTemplate jdbcTemplate, PythonBridgeFacade pythonBridgeFacade) {
        this.jdbcTemplate = jdbcTemplate;
        this.pythonBridgeFacade = pythonBridgeFacade;
    }

    /**
     * Calcula indicadores técnicos básicos procesando las velas por lotes.
     */
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

        List<IndicadorTecnico> resultados = dtos.stream()
                .filter(dto -> dto.getId() != null)
                .filter(dto -> idsValidos.contains(dto.getId()))
                .map(this::mapToEntity)
                .toList();

        if (!resultados.isEmpty()) {
            guardarIndicadoresMasivo(resultados);
            log.debug("Guardados {} indicadores del lote", resultados.size());
        }
        return resultados.size();
    }

    private List<IndicadorTecnicoDTO> leerIndicadoresDesdeRespuestaIpc(InputStream inputStream) throws IOException {
        byte[] raw = inputStream.readAllBytes();
        if (raw.length == 0) {
            return List.of();
        }

        try {
            Map<String, Object> envelope = IpcMessagePackCodec.readEnvelope(new ByteArrayInputStream(raw));
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
        } catch (Exception ex) {
            // Compatibilidad temporal: engine_indicators antiguo enviaba lista MessagePack cruda.
            return DataSerializationUtils.deserializeIndicadoresFromStream(new ByteArrayInputStream(raw), false);
        }
    }

    /**
     * Inserción en base de datos de alta velocidad saltándose la caché de
     * Hibernate.
     */
    private void guardarIndicadoresMasivo(List<IndicadorTecnico> indicadores) {
        // ATENCIÓN: Asegúrate de que los nombres de tabla y columnas coinciden con tu
        // DB real.
        String sql = "INSERT INTO indicador_tecnico (vela_id, tipo, parametros, valor, fecha_creacion, eliminado) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        final Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(@org.springframework.lang.NonNull PreparedStatement ps, int i) throws SQLException {
                IndicadorTecnico ind = indicadores.get(i);

                ps.setLong(1, ind.getVela().getId());
                ps.setString(2, ind.getTipo());
                ps.setString(3, ind.getParametros());
                ps.setBigDecimal(4, ind.getValor());
                ps.setTimestamp(5, now);
                ps.setBoolean(6, false);
            }

            @Override
            public int getBatchSize() {
                return indicadores.size();
            }
        });
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

    private IndicadorTecnico mapToEntity(IndicadorTecnicoDTO dto) {
        IndicadorTecnico ind = new IndicadorTecnico();

        // Truco de memoria: Evita que Hibernate haga un SELECT previo
        Vela v = new Vela();
        v.setId(dto.getId());

        ind.setVela(v);
        ind.setTipo(dto.getTipo());
        ind.setValor(dto.getValor());
        ind.setParametros(dto.getParametros());
        return ind;
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
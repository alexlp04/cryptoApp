package com.bottrading.services;

import com.bottrading.beans.*;
import com.bottrading.exceptions.PythonProcessException;
import com.bottrading.utils.AppConstants;
import com.bottrading.utils.DataSerializationUtils;
import com.bottrading.utils.PathConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService executor;

    private static final int BATCH_SIZE = 100000;
    private static final int OVERLAP = 50;
    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_RETRIES = 3;
    private static final int CHUNK_SIZE = 10000;

    public IndicatorsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.executor = Executors.newFixedThreadPool(4);
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
        int retries = 0;
        
        while (retries < MAX_RETRIES) {
            Process process = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(AppConstants.PYTHON_EXECUTABLE, PathConfig.INDICATORS_PATH);
                process = pb.start();

                List<VelaDTO> velasDTO = loteVelas.stream()
                        .map(this::mapToDTO)
                        .collect(Collectors.toList());

                // Streaming con MessagePack en chunks
                try (OutputStream os = process.getOutputStream()) {
                    DataSerializationUtils.streamVelasInChunks(velasDTO, os, CHUNK_SIZE, false);
                    os.flush();
                }

                // Leer resultados con timeout
                int guardados = leerResultadosDePythonYGuardar(process, loteVelas, esPrimerLote);

                // Esperar al proceso con timeout
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new PythonProcessException("Timeout en proceso Python tras " + TIMEOUT_SECONDS + "s");
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    log.warn("Python terminó con código de error {}", exitCode);
                }

                velasDTO.clear();
                return guardados;
                
            } catch (InterruptedException e) {
                retries++;
                Thread.currentThread().interrupt();
                log.warn("Reintento {} para lote (InterruptedException): {}", retries, e.getMessage());
                if (process != null) {
                    process.destroyForcibly();
                }
                if (retries >= MAX_RETRIES) {
                    throw new PythonProcessException("Fallo tras " + MAX_RETRIES + " reintentos", e);
                }
                
            } catch (IOException e) {
                if (process != null) {
                    process.destroyForcibly();
                }
                throw new PythonProcessException("Error de I/O al procesar lote: " + e.getMessage(), e);
                
            } catch (Exception e) {
                retries++;
                log.warn("Reintento {} para lote: {}", retries, e.getMessage());
                if (process != null) {
                    process.destroyForcibly();
                }
                if (retries >= MAX_RETRIES) {
                    throw new PythonProcessException("Fallo tras " + MAX_RETRIES + " reintentos", e);
                }
            }
        }
        
        return 0;
    }

    private int leerResultadosDePythonYGuardar(Process process, List<Vela> loteVelas, boolean esPrimerLote)
            throws PythonProcessException, IOException {
        try (InputStream in = process.getInputStream()) {
            // Deserializar desde MessagePack
            List<IndicadorTecnicoDTO> dtos = DataSerializationUtils.deserializeIndicadoresFromStream(in, false);

            if (dtos == null || dtos.isEmpty()) {
                return 0;
            }

            List<Long> idsValidos = obtenerIdsValidosDelLote(loteVelas, esPrimerLote);

            List<IndicadorTecnico> resultados = dtos.stream()
                    .filter(dto -> dto.getId() != null)
                    .filter(dto -> idsValidos.contains(dto.getId()))
                    .map(this::mapToEntity)
                    .toList();

            if (!resultados.isEmpty()) {
                // 🔥 Inserción ultrarrápida (Batch Insert SQL nativo)
                guardarIndicadoresMasivo(resultados);
                log.debug("Guardados {} indicadores del lote", resultados.size());
            }
            return resultados.size();
        }
    }

    /**
     * Inserción en base de datos de alta velocidad saltándose la caché de
     * Hibernate.
     */
    private void guardarIndicadoresMasivo(List<IndicadorTecnico> indicadores) {
        // ATENCIÓN: Asegúrate de que los nombres de tabla y columnas coinciden con tu
        // DB real.
        String sql = "INSERT INTO indicador_tecnico (vela_id, tipo, parametros, valor) VALUES (?, ?, ?, ?)";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(@org.springframework.lang.NonNull PreparedStatement ps, int i) throws SQLException {
                IndicadorTecnico ind = indicadores.get(i);

                ps.setLong(1, ind.getVela().getId());
                ps.setString(2, ind.getTipo());
                ps.setString(3, ind.getParametros());
                ps.setBigDecimal(4, ind.getValor());
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
    private List<Long> obtenerIdsValidosDelLote(List<Vela> loteVelas, boolean esPrimerLote) {
        int inicioReal = esPrimerLote ? 0 : OVERLAP;
        return loteVelas.subList(inicioReal, loteVelas.size()).stream()
                .map(Vela::getId)
                .toList();
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
        log.info("Iniciando shutdown de ExecutorService en IndicatorsService");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("ExecutorService no terminó en tiempo. Forzando shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Interrupción durante shutdown graceful. Forzando shutdown");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Shutdown de ExecutorService completado");
    }
}
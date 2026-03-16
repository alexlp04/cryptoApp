package com.bottrading.utils;

import com.bottrading.beans.IndicadorTecnicoDTO;
import com.bottrading.beans.VelaDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Utilitario centralizado para serialización/deserialización con MessagePack.
 * Proporciona streaming eficiente, compresión opcional, y validación básica.
 * Estándar compartido entre IndicatorsService, FetchService y otros.
 */
@Slf4j
public class DataSerializationUtils {

    private static final ObjectMapper messagePackMapper = new ObjectMapper(new MessagePackFactory());
    private static final int CHUNK_SIZE = 10000;

    /**
     * Evita que Jackson cierre el stream subyacente cuando cierra su generador/parsers.
     */
    private static final class NonClosingOutputStream extends FilterOutputStream {
        private NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void close() throws IOException {
            // Jackson llama a close() al terminar writeValue; solo flush para mantener el pipe abierto.
            flush();
        }
    }

    /**
     * Evita que Jackson cierre el stream subyacente al terminar readValue.
     */
    private static final class NonClosingInputStream extends FilterInputStream {
        private NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // No-op: el dueño del stream decide cuándo cerrarlo.
        }
    }

    /**
     * Serializa lista de VelaDTO a stream MessagePack con opción de compresión.
     * IMPORTANTE: no cierra el OutputStream subyacente cuando compress=false,
     * para permitir escrituras de múltiples chunks consecutivos.
     */
    public static void serializeVelasToStream(List<VelaDTO> velas, OutputStream out, boolean compress) throws IOException {
        long startTime = System.currentTimeMillis();
        if (compress) {
            try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
                messagePackMapper.writeValue(gzipOut, velas);
            }
        } else {
            messagePackMapper.writeValue(new NonClosingOutputStream(out), velas);
            out.flush();
        }
        log.debug("Serialización de {} velas en {} ms", velas.size(), System.currentTimeMillis() - startTime);
    }

    /**
     * Serializa lista de IndicadorTecnicoDTO a stream MessagePack con opción de compresión.
     * IMPORTANTE: no cierra el OutputStream subyacente cuando compress=false.
     */
    public static void serializeIndicadorestoStream(List<IndicadorTecnicoDTO> indicadores, OutputStream out, boolean compress) throws IOException {
        long startTime = System.currentTimeMillis();
        if (compress) {
            try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
                messagePackMapper.writeValue(gzipOut, indicadores);
            }
        } else {
            messagePackMapper.writeValue(new NonClosingOutputStream(out), indicadores);
            out.flush();
        }
        log.debug("Serialización de {} indicadores en {} ms", indicadores.size(), System.currentTimeMillis() - startTime);
    }

    /**
     * Deserializa stream MessagePack a lista de VelaDTO con soporte para descompresión.
     * IMPORTANTE: no cierra el InputStream subyacente cuando compressed=false.
     */
    public static List<VelaDTO> deserializeVelasFromStream(InputStream in, boolean compressed) throws IOException {
        long startTime = System.currentTimeMillis();
        if (compressed) {
            try (GZIPInputStream gzipIn = new GZIPInputStream(in)) {
                List<VelaDTO> velas = messagePackMapper.readValue(gzipIn,
                        messagePackMapper.getTypeFactory().constructCollectionType(List.class, VelaDTO.class));
                log.debug("Deserialización de {} velas en {} ms", velas != null ? velas.size() : 0,
                        System.currentTimeMillis() - startTime);
                return velas != null ? velas : new ArrayList<>();
            }
        } else {
            List<VelaDTO> velas = messagePackMapper.readValue(new NonClosingInputStream(in),
                    messagePackMapper.getTypeFactory().constructCollectionType(List.class, VelaDTO.class));
            log.debug("Deserialización de {} velas en {} ms", velas != null ? velas.size() : 0,
                    System.currentTimeMillis() - startTime);
            return velas != null ? velas : new ArrayList<>();
        }
    }

    /**
     * Deserializa stream MessagePack a lista de IndicadorTecnicoDTO con soporte para descompresión.
     * IMPORTANTE: no cierra el InputStream subyacente cuando compressed=false.
     */
    public static List<IndicadorTecnicoDTO> deserializeIndicadoresFromStream(InputStream in, boolean compressed) throws IOException {
        long startTime = System.currentTimeMillis();
        if (compressed) {
            try (GZIPInputStream gzipIn = new GZIPInputStream(in)) {
                List<IndicadorTecnicoDTO> indicadores = messagePackMapper.readValue(gzipIn,
                        messagePackMapper.getTypeFactory().constructCollectionType(List.class, IndicadorTecnicoDTO.class));
                log.debug("Deserialización de {} indicadores en {} ms", indicadores != null ? indicadores.size() : 0,
                        System.currentTimeMillis() - startTime);
                return indicadores != null ? indicadores : new ArrayList<>();
            }
        } else {
            List<IndicadorTecnicoDTO> indicadores = messagePackMapper.readValue(new NonClosingInputStream(in),
                    messagePackMapper.getTypeFactory().constructCollectionType(List.class, IndicadorTecnicoDTO.class));
            log.debug("Deserialización de {} indicadores en {} ms", indicadores != null ? indicadores.size() : 0,
                    System.currentTimeMillis() - startTime);
            return indicadores != null ? indicadores : new ArrayList<>();
        }
    }

    /**
     * Streaming en chunks de tamaño variable.
     * Útil para procesar datos grandes sin cargar todo en memoria.
     */
    public static void streamVelasInChunks(List<VelaDTO> velas, OutputStream out, int chunkSize, boolean compress) throws IOException {
        int totalChunks = (int) Math.ceil((double) velas.size() / chunkSize);
        log.info("Streamingo {} velas en {} chunks de tamaño {}", velas.size(), totalChunks, chunkSize);

        for (int i = 0; i < velas.size(); i += chunkSize) {
            List<VelaDTO> chunk = velas.subList(i, Math.min(i + chunkSize, velas.size()));
            serializeVelasToStream(chunk, out, compress);
        }
    }

    /**
     * Streamingg en chunks para indicadores.
     */
    public static void streamIndicadoresInChunks(List<IndicadorTecnicoDTO> indicadores, OutputStream out, int chunkSize, boolean compress) throws IOException {
        int totalChunks = (int) Math.ceil((double) indicadores.size() / chunkSize);
        log.info("Streaming {} indicadores en {} chunks de tamaño {}", indicadores.size(), totalChunks, chunkSize);

        for (int i = 0; i < indicadores.size(); i += chunkSize) {
            List<IndicadorTecnicoDTO> chunk = indicadores.subList(i, Math.min(i + chunkSize, indicadores.size()));
            serializeIndicadorestoStream(chunk, out, compress);
        }
    }

    /**
     * Validación básica: verifica que la lista no sea nula o vacía.
     */
    public static boolean validateData(List<?> data) {
        if (data == null || data.isEmpty()) {
            log.warn("Validación fallida: datos vacíos o nulos");
            return false;
        }
        return true;
    }

    /**
     * Validación de tamaño: asegura que no exceda un límite máximo.
     */
    public static boolean validateDataSize(List<?> data, int maxSize) {
        if (data.size() > maxSize) {
            log.warn("Validación fallida: tamaño {} excede máximo {}", data.size(), maxSize);
            return false;
        }
        return true;
    }
}

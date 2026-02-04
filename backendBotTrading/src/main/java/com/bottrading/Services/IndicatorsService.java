package com.bottrading.services;

import com.bottrading.beans.*;
import com.bottrading.repositories.*;
import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio encargado del cálculo de indicadores técnicos sobre los datos de mercado.
 * Delega el procesamiento matemático a un script de Python y persiste los resultados.
 */
@Service
public class IndicatorsService {

    @Autowired
    private IndicadorRepository indicadorRepo;

    @Autowired
    private VelaRepository velaRepo;

    private final Gson gson = new Gson();

    /**
     * Calcula indicadores técnicos básicos (RSI, SMA, EMA, etc.) para un conjunto de velas.
     * Envía los datos históricos al motor de Python y mapea la respuesta a entidades JPA.
     *
     * @param symbol   El par de trading (ej: BTCUSDT).
     * @param interval El intervalo de tiempo (ej: 1h).
     * @param velas    Lista de velas sobre las que calcular los indicadores.
     * @return Lista de indicadores persistidos en base de datos.
     */
    @Transactional
    public List<IndicadorTecnico> calculateBasicIndicators(String symbol, String interval, List<Vela> velas) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", PathConfig.INDICATORS_PATH);
            Process process = pb.start();

            // 1. Enviar datos al script (Stdin)
            try (OutputStream os = process.getOutputStream()) {
                os.write(gson.toJson(velas).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            // 2. Leer resultados (Stdout)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                List<IndicadorTecnicoDTO> dtos = gson.fromJson(reader,
                        new TypeToken<List<IndicadorTecnicoDTO>>() {}.getType());

                if (dtos == null || dtos.isEmpty()) {
                    return Collections.emptyList();
                }

                // Transformación DTO -> Entidad
                List<IndicadorTecnico> resultados = dtos.stream()
                        .filter(dto -> dto.getId() != null) 
                        .map(dto -> {
                            IndicadorTecnico ind = new IndicadorTecnico();
                            
                            // Aseguramos que el ID no es nulo antes de llamar al repositorio
                            Long velaId = dto.getId();
                            if (velaId == null) throw new RuntimeException("ID de vela nulo en respuesta de Python");

                            Vela v = velaRepo.findById(velaId)
                                    .orElseThrow(() -> new RuntimeException("Vela no encontrada para ID: " + velaId));
                            
                            ind.setVela(v);
                            ind.setTipo(dto.getTipo());
                            ind.setValor(dto.getValor());
                            ind.setParametros(dto.getParametros());
                            return ind;
                        }).collect(Collectors.toList());

                if (resultados == null || resultados.isEmpty()) {
                    return Collections.emptyList();
                }

                return indicadorRepo.saveAll(resultados);
            }

        } catch (Exception e) {
            throw new RuntimeException("❌ Error calculando indicadores: " + e.getMessage(), e);
        }
    }
}
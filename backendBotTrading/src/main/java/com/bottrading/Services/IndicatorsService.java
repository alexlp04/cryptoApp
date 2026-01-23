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
import java.util.List;
import java.util.stream.Collectors;

@Service
public class IndicatorsService {

    @Autowired
    private IndicadorRepository indicadorRepo;
    @Autowired
    private VelaRepository velaRepo;

    @Transactional
    public List<IndicadorTecnico> calculateBasicIndicators(String symbol, String interval, List<Vela> velas) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", PathConfig.INDICATORS_PATH);
            Process process = pb.start();

            // Enviar velas a Python vía stdin
            try (OutputStream os = process.getOutputStream()) {
                os.write(new Gson().toJson(velas).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            // Leer JSON de indicadores vía stdout
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                List<IndicadorTecnicoDTO> dtos = new Gson().fromJson(reader,
                        new TypeToken<List<IndicadorTecnicoDTO>>() {
                        }.getType());

                List<IndicadorTecnico> resultados = dtos.stream().map(dto -> {
                    IndicadorTecnico ind = new IndicadorTecnico();
                    Vela v = velaRepo.findById(dto.getId())
                            .orElseThrow(() -> new RuntimeException("Vela no encontrada"));
                    ind.setVela(v);
                    ind.setTipo(dto.getTipo());
                    ind.setValor(dto.getValor());
                    ind.setParametros(dto.getParametros());
                    return ind;
                }).collect(Collectors.toList());

                return indicadorRepo.saveAll(resultados);
            }
        } catch (Exception e) {
            throw new RuntimeException("❌ Error calculando indicadores: " + e.getMessage(), e);
        }
    }
}
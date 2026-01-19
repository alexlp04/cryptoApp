package com.bottrading.Services;

import com.bottrading.beans.IndicadorTecnico;
import com.bottrading.beans.IndicadorTecnicoDTO;
import com.bottrading.beans.Vela;
import com.bottrading.controllers.ControladorIndicador;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class IndicatorsService {

    private ControladorIndicador controladorIndicador = new ControladorIndicador();
    private static final String INDICATORS_ENGINE_PATH = "D:\\Users\\Alejandro\\Documents\\Informatica\\cryptoApp\\python-scripts\\indicators.py";
    
    public IndicatorsService() {
    }

    public List<IndicadorTecnico> calculateBasicIndicators(String symbol, String interval, List<Vela> velas) {
        System.out.println("Calculando indicadores para " + symbol + " en intervalo " + interval);
        List<IndicadorTecnicoDTO> indicadoresDTO = new ArrayList<>();
        List<IndicadorTecnico> indicadoresTecnicos = new ArrayList<>();
        try {
            ProcessBuilder pb;
            pb = new ProcessBuilder("python3", INDICATORS_ENGINE_PATH);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            Gson gsonVelas = new Gson();
            String jsonVelas = gsonVelas.toJson(velas);

            OutputStream os = process.getOutputStream();
            os.write(jsonVelas.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;

            System.out.println("Leyendo datos de la salida del proceso Python...");

            while ((line = reader.readLine()) != null) {
                System.err.println(line);
                output.append(line);
            }

            // Ahora output contiene todo el JSON
            String jsonOutput = output.toString();
            System.out.println("DEBUG JSON recibido: " + jsonOutput);

            Gson gson = new Gson();
            Type listType = new TypeToken<List<IndicadorTecnicoDTO>>() {
            }.getType();
            indicadoresDTO = gson.fromJson(jsonOutput, listType);
            indicadoresDTO.stream().forEach(
                    indicadorDTO -> indicadoresTecnicos.add(controladorIndicador.guardarIndicador(indicadorDTO)));
            System.out.println("Guardadas " + indicadoresDTO.size() + " velas en la BD (batch incremental).");

        } catch (Exception e) {
            e.printStackTrace();
        }
        return indicadoresTecnicos;

    }

}

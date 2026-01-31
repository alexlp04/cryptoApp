package com.bottrading.services;

import com.bottrading.beans.InstanciaEstrategia;
import com.bottrading.beans.SignalDTO;
import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TradingService {

    @Autowired
    private PaperTradingService paperTradingService;
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newCachedThreadPool();


    public void ejecutarTradeEnTiempoReal(InstanciaEstrategia instancia, List<String> symbols) {
            runEngineRT(instancia, symbols);
        
    }

    private void runEngineRT(InstanciaEstrategia instancia, List<String> symbols) {
        try {
            // Lanza el motor de Python
            ProcessBuilder pb = new ProcessBuilder("python3", PathConfig.ENGINE_RT_PATH);
            Process process = pb.start();

            // 1. Enviar configuración inicial a Python
            enviarPayload(process, instancia, symbols);

            // 2. Escuchar la consola de Python para capturar señales JSON
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("{")) {
                        SignalDTO signal = gson.fromJson(line, SignalDTO.class);
                        // ENVIAR SEÑAL AL SERVICIO DE TRADING
                        paperTradingService.onSignal(instancia.getId(), signal);
                    } else {
                        System.out.println("LOG [" + "]: " + line);
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            System.err.println("Error en motor Python para " + symbols + ": " + e.getMessage());
        }
    }

    private void enviarPayload(Process p, InstanciaEstrategia inst, List<String> symbols) throws IOException {
   
        Map<String, Object> payload = Map.of(
                "strategy_path", PathConfig.getValidStrategyPath(inst.getNombreEstrategia()),
                "symbols", new ArrayList<>(symbols),
                "timeframe", inst.getTimeframe(),
                "capital", inst.getCapitalReservado());
        try (OutputStream os = p.getOutputStream()) {
            os.write(gson.toJson(payload).getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }
}
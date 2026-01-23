package com.bottrading.Services;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.bottrading.Utils.PathConfig;
import com.bottrading.beans.SignalDTO;

public class StrategyService {

    private PaperTradingService paperTrading;

    public StrategyService(PaperTradingService paperTrading) {
        this.paperTrading = paperTrading;
    }

    public void ejecutarTradeEnTiempoReal(String rutaEstrategia, String timeframe, List<String> symbols,
            boolean isReal) {
        for (String symbol : symbols) {
            new Thread(() -> runEngineRT(rutaEstrategia, symbol, timeframe, isReal)).start();
        }
    }

    private void runEngineRT(String rutaEstrategia, String symbol, String timeframe, boolean isReal) {
    try {
        ProcessBuilder pb = new ProcessBuilder("python", PathConfig.ENGINE_RT_PATH);
        pb.redirectErrorStream(false); // Separamos flujos para capturar errores de Python
        Process process = pb.start();

        Map<String, Object> payload = Map.of(
                "strategy_path", rutaEstrategia,
                "symbols", List.of(symbol),
                "timeframe", timeframe,
                "is_real", isReal);

        String jsonPayload = new com.google.gson.Gson().toJson(payload);

        try (OutputStream os = process.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        // Hilo para capturar errores de Python (Vital para diagnóstico)
        new Thread(() -> {
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String errLine;
                while ((errLine = errReader.readLine()) != null) {
                    System.err.println("❌ [Engine Python Error]: " + errLine);
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();

        // Lectura de señales (Salida estándar)
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            com.google.gson.Gson gson = new com.google.gson.Gson();
            while ((line = reader.readLine()) != null) {
                String trimmedLine = line.trim();
                if (trimmedLine.startsWith("{")) { // Solo procesamos si parece un JSON
                    SignalDTO signal = gson.fromJson(trimmedLine, SignalDTO.class);
                    // Aquí delegamos la gestión del dinero a PaperTradingService
                    paperTrading.onSignal(signal);
                } else {
                    System.out.println("LOG [Python]: " + trimmedLine);
                }
            }
        }

        process.waitFor();

    } catch (Exception e) {
        System.err.println("❌ Fallo en el hilo de ejecución RT para " + symbol + ": " + e.getMessage());
    }
}

}

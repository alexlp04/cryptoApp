package com.bottrading.services;

import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class SaveFileService {

    private final Gson gson = new Gson();

    /**
     * Procesa y guarda los resultados complejos (Trades y Stats) que vienen de un
     * motor (Backtest).
     */
    @SuppressWarnings("unchecked")
    public void guardarResultadosCompletos(String nombreEstrategia, String timeframe, String jsonResultado)
            throws Exception {
        Map<String, Object> resultado = gson.fromJson(jsonResultado, new TypeToken<Map<String, Object>>() {
        }.getType());

        List<Map<String, Object>> trades = (List<Map<String, Object>>) resultado.get("trades");
        List<Map<String, Object>> stats = (List<Map<String, Object>>) resultado.get("stats");

        if (trades != null) {
            for (Map<String, Object> trade : trades) {
                guardarTrade(nombreEstrategia, timeframe, trade, true);
            }
        }

        if (stats != null) {
            for (Map<String, Object> stat : stats) {
                guardarStats(nombreEstrategia, timeframe, stat, true);
            }
        }
    }

    /**
     * Guarda un trade individual en formato CSV.
     */
    public synchronized void guardarTrade(String nombreEstrategia, String timeframe, Map<String, Object> trade,
            boolean isBacktest) {
        try {
            String symbol = trade.get("symbol").toString();
            String suffix = isBacktest ? "_backtest.csv" : ".csv";
            Path filePath = getCarpetaEstrategia(nombreEstrategia)
                    .resolve("trades-" + timeframe + "-" + symbol + suffix);

            boolean nuevo = !Files.exists(filePath);

            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath,
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND))) {

                if (nuevo) {
                    pw.println("symbol,timeframe,side,price,timestamp,pnl,capital,max_drawdown");
                }

                pw.printf("%s,%s,%s,%s,%s,%s,%s,%s%n",
                        trade.get("symbol"),
                        timeframe,
                        trade.get("side"),
                        trade.get("price"),
                        trade.get("timestamp"),
                        trade.getOrDefault("pnl", ""),
                        trade.getOrDefault("capital", ""),
                        trade.getOrDefault("max_drawdown", ""));
            }
        } catch (IOException e) {
            System.err.println("❌ Error al guardar trade: " + e.getMessage());
        }
    }

    /**
     * Guarda las estadísticas finales en CSV, reemplazando la fila del símbolo si
     * ya existe.
     */
    public synchronized void guardarStats(String nombreEstrategia, String timeframe, Map<String, Object> stats,
            boolean isBacktest) {
        try {
            String suffix = isBacktest ? "_backtest.csv" : ".csv";
            Path filePath = getCarpetaEstrategia(nombreEstrategia).resolve("results-" + timeframe + suffix);

            List<Map<String, Object>> rows = new ArrayList<>();

            // 1. Leer existentes para actualizar
            if (Files.exists(filePath)) {
                try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                    br.readLine(); // saltar header
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] p = line.split(",", -1);
                        if (p.length < 14)
                            continue;
                        Map<String, Object> row = new HashMap<>();
                        row.put("symbol", p[0]);
                        row.put("timeframe", p[1]);
                        row.put("op_ganadas", p[2]);
                        row.put("op_perdidas", p[3]);
                        row.put("op_totales", p[4]);
                        row.put("max_drawdown", p[5]);
                        row.put("abs_drawdown", p[6]);
                        row.put("retorno_acumulado", p[7]);
                        row.put("retorno_total", p[8]);
                        row.put("win_rate", p[9]);
                        row.put("profit_factor", p[10]);
                        row.put("fecha_inicio", p[11]);
                        row.put("fecha_fin", p[12]);
                        row.put("resultado", p[13]);
                        rows.add(row);
                    }
                }
            }

            // 2. Reemplazar y añadir
            rows.removeIf(r -> r.get("symbol").equals(stats.get("symbol")));
            rows.add(stats);

            // 3. Escribir archivo completo
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath, StandardCharsets.UTF_8))) {
                pw.println("symbol,timeframe,op_ganadas,op_perdidas,op_totales,max_drawdown,abs_drawdown," +
                        "retorno_acumulado,retorno_total,win_rate,profit_factor,fecha_inicio,fecha_fin,resultado");

                for (Map<String, Object> r : rows) {
                    pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                            r.get("symbol"), r.get("timeframe"), r.get("op_ganadas"), r.get("op_perdidas"),
                            r.get("op_totales"), r.get("max_drawdown"), r.get("abs_drawdown"),
                            r.get("retorno_acumulado"), r.get("retorno_total"), r.get("win_rate"),
                            r.get("profit_factor"), r.get("fecha_inicio"), r.get("fecha_fin"), r.get("resultado"));
                }
            }
        } catch (IOException e) {
            System.err.println("❌ Error al guardar stats: " + e.getMessage());
        }
    }

    private Path getCarpetaEstrategia(String nombreEstrategia) throws IOException {
        Path path = Paths.get(PathConfig.RESULTS_DIR, nombreEstrategia + "-results");
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
        return path;
    }
}
package com.bottrading.Services;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class SaveFileService {

    /**
     * Guarda un CSV de los trades y estadísticas devueltos por el engine.
     * El CSV se guarda en la misma carpeta que el fichero de estrategia.
     *
     * @param rutaEstrategia ruta del fichero .py de la estrategia
     * @param jsonResultado  JSON devuelto por el engine Python
     * @throws Exception si falla la escritura
     */
    public void guardarCSVBacktest(String rutaEstrategia, String jsonResultado) throws Exception {

        Gson gson = new Gson();
        Map<String, Object> resultadoMap = gson.fromJson(jsonResultado,
                new TypeToken<Map<String, Object>>() {}.getType());

        List<Map<String, Object>> trades = (List<Map<String, Object>>) resultadoMap.get("trades");
        List<Map<String, Object>> statsList = (List<Map<String, Object>>) resultadoMap.get("stats");

        // Ruta del CSV: misma carpeta que la estrategia
        java.io.File estrategiaFile = new java.io.File(rutaEstrategia);
        String carpeta = estrategiaFile.getParent();
        String nombreCsv = estrategiaFile.getName().replace(".py", "_backtest.csv");
        java.io.File csvFile = new java.io.File(carpeta, nombreCsv);

        try (PrintWriter pw = new PrintWriter(new FileWriter(csvFile, StandardCharsets.UTF_8))) {

            // --- Escribimos primero los stats de cada símbolo ---
            pw.println("symbol,timeframe,op_ganadas,op_perdidas,op_totales,max_drawdown,abs_drawdown,"
                    + "retorno_acumulado,retorno_total,win_rate,profit_factor,fecha_inicio,fecha_fin,resultado");

            for (Map<String, Object> stats : statsList) {
                pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                        stats.get("symbol"),
                        stats.get("timeframe"),
                        stats.get("op_ganadas"),
                        stats.get("op_perdidas"),
                        stats.get("op_totales"),
                        stats.get("max_drawdown"),
                        stats.get("abs_drawdown"),
                        stats.get("retorno_acumulado"),
                        stats.get("retorno_total"),
                        stats.get("win_rate"),
                        stats.get("profit_factor"),
                        stats.get("fecha_inicio"),
                        stats.get("fecha_fin"),
                        stats.get("resultado")
                );
            }

            pw.println(); // línea vacía entre stats y trades

            // --- Luego escribimos los trades ---
            if (trades != null && !trades.isEmpty()) {
                pw.println("symbol,side,price,timestamp,pnl,capital");
                for (Map<String, Object> trade : trades) {
                    pw.printf("%s,%s,%s,%s,%s,%s%n",
                            trade.get("symbol"),
                            trade.get("side"),
                            trade.get("price"),
                            trade.get("timestamp"),
                            trade.getOrDefault("pnl", ""),
                            trade.getOrDefault("capital", "")
                    );
                }
            }

        }

        System.out.println("CSV completo guardado en: " + csvFile.getAbsolutePath());
    }
}

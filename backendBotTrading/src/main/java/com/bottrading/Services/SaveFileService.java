package com.bottrading.Services;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class SaveFileService {

    private final File carpetaResultados;

    public SaveFileService(String rutaEstrategia) {
        File estrategiaFile = new File(rutaEstrategia);
        String nombre = estrategiaFile.getName().replace(".py", "");
        this.carpetaResultados = new File(
                estrategiaFile.getParent(),
                nombre + "-results");
        if (!carpetaResultados.exists())
            carpetaResultados.mkdirs();
    }

    @SuppressWarnings("unchecked")
    public void guardarResultados(String timeframe, String jsonResultado) throws Exception {

        Gson gson = new Gson();
        Map<String, Object> resultado = gson.fromJson(jsonResultado, new TypeToken<Map<String, Object>>() {
        }.getType());

        List<Map<String, Object>> trades = (List<Map<String, Object>>) resultado.get("trades");

        List<Map<String, Object>> stats = (List<Map<String, Object>>) resultado.get("stats");

        // ---------- TRADES ----------
        if (trades != null) {
            for (Map<String, Object> trade : trades) {
                guardarTradeBacktest(timeframe, trade);
            }
        }

        // ---------- RESULTS ----------
        if (stats != null) {
            for (Map<String, Object> stat : stats) {
                guardarResultadosBacktest(timeframe, stat);
            }
        }
    }

    private synchronized void guardarTradeBacktest(String timeframe, Map<String, Object> trade) {

        try {
            String symbol = trade.get("symbol").toString();
            File file = new File(
                    carpetaResultados,
                    "trades-" + timeframe + "-" + symbol + "_backtest.csv");

            boolean nuevo = !file.exists();

            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(file, true),
                            StandardCharsets.UTF_8))) {
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

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private synchronized void guardarResultadosBacktest(String timeframe, Map<String, Object> stats) {

        try {
            File file = new File(
                    carpetaResultados,
                    "results-" + timeframe + "_backtest.csv");

            List<Map<String, Object>> rows = new ArrayList<>();

            if (file.exists()) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(file),
                                StandardCharsets.UTF_8))) {
                    br.readLine(); // header
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] p = line.split(",", -1);
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

            rows.removeIf(r -> r.get("symbol").equals(stats.get("symbol")));
            rows.add(stats);

            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(file),
                            StandardCharsets.UTF_8))) {
                pw.println("symbol,timeframe,op_ganadas,op_perdidas,op_totales,max_drawdown,abs_drawdown," +
                        "retorno_acumulado,retorno_total,win_rate,profit_factor,fecha_inicio,fecha_fin,resultado");

                for (Map<String, Object> r : rows) {
                    pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                            r.get("symbol"),
                            r.get("timeframe"),
                            r.get("op_ganadas"),
                            r.get("op_perdidas"),
                            r.get("op_totales"),
                            r.get("max_drawdown"),
                            r.get("abs_drawdown"),
                            r.get("retorno_acumulado"),
                            r.get("retorno_total"),
                            r.get("win_rate"),
                            r.get("profit_factor"),
                            r.get("fecha_inicio"),
                            r.get("fecha_fin"),
                            r.get("resultado"));
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
     * ============================================================
     * =============== TRADES (1 archivo por símbolo) ===============
     * ============================================================
     */
    public synchronized void guardarTrade(String timeframe, Map<String, Object> trade) {
        try {
            String symbol = trade.get("symbol").toString();
            File file = new File(
                    carpetaResultados,
                    "trades-" + timeframe + "-" + symbol + ".csv");

            boolean nuevo = !file.exists();

            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(file, true),
                            StandardCharsets.UTF_8))) {
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

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
     * ============================================================
     * =============== RESULTS (1 archivo, N símbolos) ===============
     * ============================================================
     */
    public synchronized void guardarResultados(String timeframe, Map<String, Object> stats) {
        try {
            File file = new File(carpetaResultados, "results-" + timeframe + ".csv");

            List<Map<String, Object>> rows = new ArrayList<>();

            // Si existe, leemos todo
            if (file.exists()) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(file),
                                StandardCharsets.UTF_8))) {
                    // String header = br.readLine(); // skip header
                    String line;

                    while ((line = br.readLine()) != null) {
                        String[] p = line.split(",", -1);
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

            // Eliminamos stats antiguos del mismo símbolo
            rows.removeIf(r -> r.get("symbol").equals(stats.get("symbol")));

            rows.add(stats);

            // Reescribimos completo
            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(file),
                            StandardCharsets.UTF_8))) {
                pw.println("symbol,timeframe,op_ganadas,op_perdidas,op_totales,max_drawdown,abs_drawdown," +
                        "retorno_acumulado,retorno_total,win_rate,profit_factor,fecha_inicio,fecha_fin,resultado");

                for (Map<String, Object> r : rows) {
                    pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                            r.get("symbol"),
                            r.get("timeframe"),
                            r.get("op_ganadas"),
                            r.get("op_perdidas"),
                            r.get("op_totales"),
                            r.get("max_drawdown"),
                            r.get("abs_drawdown"),
                            r.get("retorno_acumulado"),
                            r.get("retorno_total"),
                            r.get("win_rate"),
                            r.get("profit_factor"),
                            r.get("fecha_inicio"),
                            r.get("fecha_fin"),
                            r.get("resultado"));
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

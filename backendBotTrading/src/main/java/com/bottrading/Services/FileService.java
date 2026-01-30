package com.bottrading.services;

import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

@Service
public class FileService {

    private final Gson gson = new Gson();

    public void guardarResultadosCompletos(String nombreEstrategia, String timeframe, String jsonResultado)
            throws Exception {
        Map<String, Object> resultado = gson.fromJson(jsonResultado, new TypeToken<Map<String, Object>>() {
        }.getType());

        List<Map<String, Object>> trades = (List<Map<String, Object>>) resultado.get("trades");
        List<Map<String, Object>> statsList = (List<Map<String, Object>>) resultado.get("stats");

        // 1. Guardar trades: cada uno a su archivo MONEDA-TF-trades_backtest.csv
        if (trades != null) {
            for (Map<String, Object> trade : trades) {
                // Extraemos el símbolo real del trade (BTCUSDT, ETHUSDT...)
                String symbol = trade.get("symbol").toString();
                guardarTrade(nombreEstrategia, timeframe, symbol, trade, true);
            }
        }

        // 2. Guardar estadísticas: al archivo consolidado results_backtest.csv
        if (statsList != null) {
            for (Map<String, Object> stats : statsList) {
                guardarStats(nombreEstrategia, timeframe, stats, true);
            }
        }
    }

    // Sobrecargamos o modificamos el método guardarTrade para que reciba el símbolo
    // explícito
    public synchronized void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            Map<String, Object> trade, boolean isBacktest) {
        try {
            String suffix = isBacktest ? "_backtest.csv" : ".csv";
            // Nombre dinámico basado en el símbolo real del trade
            String fileName = String.format("%s-%s-trades%s", symbol, timeframe, suffix);
            Path filePath = getCarpetaEstrategia(nombreEstrategia).resolve(fileName);

            boolean nuevo = !Files.exists(filePath);

            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {

                if (nuevo) {
                    pw.println("symbol,timeframe,side,price,timestamp,pnl,capital");
                }

                pw.printf("%s,%s,%s,%s,%s,%s,%s%n",
                        symbol,
                        timeframe,
                        trade.get("side"),
                        trade.get("price"),
                        trade.get("timestamp"),
                        trade.getOrDefault("pnl", ""),
                        trade.getOrDefault("capital", ""));
            }
        } catch (IOException e) {
            System.err.println("Error al guardar trade: " + e.getMessage());
        }
    }

    public synchronized void guardarStats(String nombreEstrategia, String timeframe, Map<String, Object> stats,
            boolean isBacktest) {
        try {
            String suffix = isBacktest ? "_backtest.csv" : ".csv";
            Path filePath = getCarpetaEstrategia(nombreEstrategia).resolve("results" + suffix);

            List<Map<String, Object>> rows = new ArrayList<>();

            // 1. Leer existentes para NO PERDER los datos de otras monedas
            if (Files.exists(filePath)) {
                try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                    String header = br.readLine(); // saltar header
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

            // 2. Eliminar la versión antigua de esta moneda (si existe) para poner la nueva
            String currentSymbol = stats.get("symbol").toString();
            rows.removeIf(r -> r.get("symbol").equals(currentSymbol) && r.get("timeframe").equals(timeframe));

            // Añadimos los nuevos stats
            rows.add(stats);

            // 3. Escribir todo de nuevo sin nulls
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath, StandardCharsets.UTF_8))) {
                pw.println("symbol,timeframe,op_ganadas,op_perdidas,op_totales,max_drawdown,abs_drawdown," +
                        "retorno_acumulado,retorno_total,win_rate,profit_factor,fecha_inicio,fecha_fin,resultado");

                for (Map<String, Object> r : rows) {
                    pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                            getSafe(r, "symbol"),
                            getSafe(r, "timeframe"),
                            getSafe(r, "op_ganadas"),
                            getSafe(r, "op_perdidas"),
                            getSafe(r, "op_totales"),
                            getSafe(r, "max_drawdown"),
                            getSafe(r, "abs_drawdown"),
                            getSafe(r, "retorno_acumulado"),
                            getSafe(r, "retorno_total"),
                            getSafe(r, "win_rate"),
                            getSafe(r, "profit_factor"),
                            getSafe(r, "fecha_inicio"),
                            getSafe(r, "fecha_fin"),
                            getSafe(r, "resultado"));
                }
            }
        } catch (IOException e) {
            System.err.println("Error al guardar stats: " + e.getMessage());
        }
    }

    // Función auxiliar para evitar los "null" visuales en el CSV
    private String getSafe(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return (val == null) ? "" : val.toString();
    }

    private Path getCarpetaEstrategia(String nombreEstrategia) throws IOException {
        // La carpeta ahora se llama simplemente "NombreEstrategia" dentro de results
        Path path = Paths.get(PathConfig.RESULTS_DIR, nombreEstrategia);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
        return path;
    }

    public synchronized void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            String side, BigDecimal price, long timestamp,
            BigDecimal pnl, BigDecimal capital, boolean isBacktest) {
        try {
            Path carpeta = getCarpetaEstrategia(nombreEstrategia);

            String suffix = isBacktest ? "_backtest.csv" : ".csv";
            String fileName = String.format("%s-%s-trades%s", symbol, timeframe, suffix);
            Path filePath = carpeta.resolve(fileName);

            boolean esNuevo = !Files.exists(filePath);

            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {

                if (esNuevo) {
                    pw.println("symbol,timeframe,side,price,timestamp,pnl,capital");
                }

                pw.printf("%s,%s,%s,%.8f,%d,%s,%s%n",
                        symbol,
                        timeframe,
                        side,
                        price,
                        timestamp,
                        (pnl != null ? String.format("%.8f", pnl) : ""),
                        (capital != null ? String.format("%.2f", capital) : ""));
            }
        } catch (IOException e) {
            System.err.println("Error al guardar trade individual: " + e.getMessage());
        }
    }

    public void verificarYLimpiarCarpetaEstrategia(String nombreEstrategia) {
        Path carpeta = Paths.get(PathConfig.RESULTS_DIR, nombreEstrategia);

        if (Files.exists(carpeta)) {
            System.out.println("\nLa carpeta de resultados '" + nombreEstrategia + "' ya existe.");
            System.out.print("¿Deseas eliminar los archivos de BACKTEST anteriores antes de empezar? (s/n): ");

            Scanner sc = new Scanner(System.in);
            String respuesta = sc.nextLine().trim().toLowerCase();

            if (respuesta.equals("s")) {
                try (var stream = Files.list(carpeta)) {
                    stream.filter(path -> path.getFileName().toString().contains("backtest"))
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    System.err.println("No se pudo borrar: " + path.getFileName());
                                }
                            });
                    System.out.println("Archivos de backtest antiguos eliminados.");
                } catch (IOException e) {
                    System.err.println("Error al acceder a la carpeta: " + e.getMessage());
                }
            } else {
                System.out.println("Manteniendo archivos anteriores. Los nuevos datos se añadirán al final.");
            }
        }
    }

}
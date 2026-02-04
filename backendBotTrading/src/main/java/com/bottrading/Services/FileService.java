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

/**
 * Servicio encargado de la persistencia de datos en el sistema de archivos (CSV).
 * Gestiona el guardado de trades individuales, estadísticas agregadas y limpieza de directorios.
 */
@Service
public class FileService {

    private final Gson gson = new Gson();

    /**
     * Procesa y guarda los resultados completos de un backtest (Trades + Estadísticas).
     *
     * @param nombreEstrategia Nombre de la estrategia ejecutada.
     * @param timeframe        Marco temporal utilizado.
     * @param jsonResultado    JSON crudo devuelto por el motor de Python.
     * @throws Exception Si ocurre un error de parseo o escritura.
     */
    public void guardarResultadosCompletos(String nombreEstrategia, String timeframe, String jsonResultado)
            throws Exception {
        
        Map<String, Object> resultado = gson.fromJson(jsonResultado, new TypeToken<Map<String, Object>>() {}.getType());

        // Serializamos y deserializamos de nuevo para obtener los tipos concretos sin warnings
        String tradesJson = gson.toJson(resultado.get("trades"));
        List<Map<String, Object>> trades = gson.fromJson(tradesJson, new TypeToken<List<Map<String, Object>>>() {}.getType());

        String statsJson = gson.toJson(resultado.get("stats"));
        List<Map<String, Object>> statsList = gson.fromJson(statsJson, new TypeToken<List<Map<String, Object>>>() {}.getType());

        if (trades != null) {
            for (Map<String, Object> trade : trades) {
                String symbol = trade.get("symbol").toString();
                guardarTrade(nombreEstrategia, timeframe, symbol, trade, true);
            }
        }

        if (statsList != null) {
            for (Map<String, Object> stats : statsList) {
                guardarStats(nombreEstrategia, timeframe, stats, true);
            }
        }
    }

    /**
     * Guarda un trade individual en su archivo CSV correspondiente.
     * Si el archivo no existe, crea la cabecera.
     * * @param nombreEstrategia Nombre de la estrategia.
     * @param timeframe        Timeframe.
     * @param symbol           Símbolo del trade.
     * @param trade            Datos del trade (precio, PnL, timestamp, etc.).
     * @param isBacktest       True si es simulación, False si es tiempo real.
     */
    public synchronized void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            Map<String, Object> trade, boolean isBacktest) {
        try {
            String suffix = isBacktest ? "_backtest.csv" : ".csv";
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

    /**
     * Sobrecarga de `guardarTrade` para uso manual desde Java con tipos fuertes.
     */
    public synchronized void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            String side, BigDecimal price, long timestamp,
            BigDecimal pnl, BigDecimal capital, boolean isBacktest) {
        Map<String, Object> map = new HashMap<>();
        map.put("side", side);
        map.put("price", price);
        map.put("timestamp", timestamp);
        if (pnl != null) map.put("pnl", String.format("%.8f", pnl));
        if (capital != null) map.put("capital", String.format("%.2f", capital));
        
        guardarTrade(nombreEstrategia, timeframe, symbol, map, isBacktest);
    }

    /**
     * Actualiza el archivo de estadísticas acumuladas (results.csv).
     * Si ya existe una entrada para ese símbolo y timeframe, la sobrescribe.
     *
     * @param nombreEstrategia Nombre de la estrategia.
     * @param timeframe        Timeframe.
     * @param stats            Mapa con las métricas (win_rate, drawdown, etc.).
     * @param isBacktest       True si es simulación.
     */
    public synchronized void guardarStats(String nombreEstrategia, String timeframe, Map<String, Object> stats,
            boolean isBacktest) {
        try {
            String suffix = isBacktest ? "_backtest.csv" : ".csv";
            Path filePath = getCarpetaEstrategia(nombreEstrategia).resolve("results" + suffix);

            List<Map<String, Object>> rows = new ArrayList<>();

            // 1. Leer estadísticas previas para preservar datos de otros pares
            if (Files.exists(filePath)) {
                try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                    br.readLine();
                    
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] p = line.split(",", -1);
                        if (p.length < 14) continue;

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

            // 2. Reemplazar o añadir la nueva estadística
            String currentSymbol = stats.get("symbol").toString();
            rows.removeIf(r -> r.get("symbol").equals(currentSymbol) && r.get("timeframe").equals(timeframe));
            rows.add(stats);

            // 3. Reescribir el archivo completo
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath, StandardCharsets.UTF_8))) {
                pw.println("symbol,timeframe,op_ganadas,op_perdidas,op_totales,max_drawdown,abs_drawdown," +
                        "retorno_acumulado,retorno_total,win_rate,profit_factor,fecha_inicio,fecha_fin,resultado");

                for (Map<String, Object> r : rows) {
                    pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                            getSafe(r, "symbol"), getSafe(r, "timeframe"), getSafe(r, "op_ganadas"),
                            getSafe(r, "op_perdidas"), getSafe(r, "op_totales"), getSafe(r, "max_drawdown"),
                            getSafe(r, "abs_drawdown"), getSafe(r, "retorno_acumulado"), getSafe(r, "retorno_total"),
                            getSafe(r, "win_rate"), getSafe(r, "profit_factor"), getSafe(r, "fecha_inicio"),
                            getSafe(r, "fecha_fin"), getSafe(r, "resultado"));
                }
            }
        } catch (IOException e) {
            System.err.println("Error al guardar stats: " + e.getMessage());
        }
    }

    /**
     * Limpia los archivos de backtest antiguos si el usuario lo confirma.
     */
    public void verificarYLimpiarCarpetaEstrategia(String nombreEstrategia) {
        Path carpeta = Paths.get(PathConfig.RESULTS_DIR, nombreEstrategia);

        if (Files.exists(carpeta)) {
            System.out.println("\nLa carpeta de resultados '" + nombreEstrategia + "' ya existe.");
            System.out.print("¿Deseas eliminar los archivos de BACKTEST anteriores antes de empezar? (s/n): ");

            try (Scanner sc = new Scanner(System.in)) {
                String respuesta = sc.nextLine().trim().toLowerCase();
                if (respuesta.equals("s")) {
                    limpiarArchivosBacktest(carpeta);
                } else {
                    System.out.println("Manteniendo archivos anteriores.");
                }
            } 

        }
    }
    
    // Método auxiliar para limpieza
    private void limpiarArchivosBacktest(Path carpeta) {
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
    }

    /**
     * Lee las estadísticas actuales desde el CSV para permitir actualizaciones incrementales en tiempo real.
     */
    public Map<String, Object> leerStatsActuales(String nombreEstrategia, String timeframe, String symbol) {
        Map<String, Object> stats = new HashMap<>();
        Path filePath = Paths.get(PathConfig.RESULTS_DIR, nombreEstrategia, "results.csv");

        if (Files.exists(filePath)) {
            try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                br.readLine(); // Ignorar header
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split(",", -1);
                    if (p.length >= 14 && p[0].equals(symbol) && p[1].equals(timeframe)) {
                        stats.put("symbol", p[0]);
                        stats.put("timeframe", p[1]);
                        stats.put("op_ganadas", p[2]);
                        stats.put("op_perdidas", p[3]);
                        stats.put("op_totales", p[4]);
                        stats.put("max_drawdown", p[5]);
                        stats.put("abs_drawdown", p[6]);
                        stats.put("retorno_acumulado", p[7]);
                        stats.put("retorno_total", p[8]);
                        stats.put("win_rate", p[9]);
                        stats.put("profit_factor", p[10]);
                        stats.put("fecha_inicio", p[11]);
                        stats.put("fecha_fin", p[12]);
                        stats.put("resultado", p[13]);
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al leer stats: " + e.getMessage());
            }
        }
        return stats;
    }

    private String getSafe(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return (val == null) ? "" : val.toString();
    }

    private Path getCarpetaEstrategia(String nombreEstrategia) throws IOException {
        Path path = Paths.get(PathConfig.RESULTS_DIR, nombreEstrategia);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
        return path;
    }
}
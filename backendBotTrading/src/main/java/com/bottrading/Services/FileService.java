package com.bottrading.services;

import com.bottrading.utils.AppConstants;
import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import lombok.extern.slf4j.Slf4j;

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
@Slf4j
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
                String symbol = trade.get(AppConstants.KEY_SYMBOL).toString();
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
                    pw.println(AppConstants.CSV_HEADER_TRADES);
                }

                pw.printf("%s,%s,%s,%s,%s,%s,%s%n",
                        symbol,
                        timeframe,
                        trade.get(AppConstants.KEY_SIDE),
                        trade.get(AppConstants.KEY_PRICE),
                        trade.get(AppConstants.KEY_TIMESTAMP),
                        trade.getOrDefault(AppConstants.KEY_PNL, ""),
                        trade.getOrDefault(AppConstants.KEY_CAPITAL, ""));
            }
        } catch (IOException e) {
            log.error("Error al guardar trade: {}", e.getMessage());
        }
    }

    /**
     * Sobrecarga de `guardarTrade` para uso manual desde Java con tipos fuertes.
     */
    public synchronized void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            String side, BigDecimal price, long timestamp,
            BigDecimal pnl, BigDecimal capital, boolean isBacktest) {
        Map<String, Object> map = new HashMap<>();
        map.put(AppConstants.KEY_SIDE, side);
        map.put(AppConstants.KEY_PRICE, price);
        map.put(AppConstants.KEY_TIMESTAMP, timestamp);
        if (pnl != null) map.put(AppConstants.KEY_PNL, String.format("%.8f", pnl));
        if (capital != null) map.put(AppConstants.KEY_CAPITAL, String.format("%.2f", capital));
        
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

            if (Files.exists(filePath)) {
                try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                    br.readLine(); // Skip header
                    String line;
                    while ((line = br.readLine()) != null) {
                        // SOLUCIÓN: Usamos parseCsvLine en lugar de split sencillo
                        String[] p = parseCsvLine(line);
                        
                        if (p.length < 14) continue;

                        Map<String, Object> row = new HashMap<>();
                        row.put(AppConstants.KEY_SYMBOL, p[0]);
                        row.put(AppConstants.KEY_TIMEFRAME, p[1]);
                        row.put(AppConstants.KEY_OP_GANADAS, p[2]);
                        row.put(AppConstants.KEY_OP_PERDIDAS, p[3]);
                        row.put(AppConstants.KEY_OP_TOTALES, p[4]);
                        row.put(AppConstants.KEY_MAX_DRAWDOWN, p[5]);
                        row.put(AppConstants.KEY_ABS_DRAWDOWN, p[6]);
                        row.put(AppConstants.KEY_RET_ACUMULADO, p[7]);
                        row.put(AppConstants.KEY_RET_TOTAL, p[8]);
                        row.put(AppConstants.KEY_WIN_RATE, p[9]);
                        row.put(AppConstants.KEY_PROFIT_FACTOR, p[10]);
                        row.put(AppConstants.KEY_FECHA_INICIO, p[11]);
                        row.put(AppConstants.KEY_FECHA_FIN, p[12]);
                        row.put(AppConstants.KEY_RESULTADO, p[13]);
                        rows.add(row);
                    }
                }
            }

            // Reemplazar o añadir
            String currentSymbol = stats.get(AppConstants.KEY_SYMBOL).toString();
            rows.removeIf(r -> r.get(AppConstants.KEY_SYMBOL).equals(currentSymbol) && r.get(AppConstants.KEY_TIMEFRAME).equals(timeframe));
            rows.add(stats);

            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath, StandardCharsets.UTF_8))) {
                pw.println(AppConstants.CSV_HEADER_STATS);

                for (Map<String, Object> r : rows) {
                    pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                            getSafe(r, AppConstants.KEY_SYMBOL), 
                            getSafe(r, AppConstants.KEY_TIMEFRAME), 
                            getSafe(r, AppConstants.KEY_OP_GANADAS),
                            getSafe(r, AppConstants.KEY_OP_PERDIDAS), 
                            getSafe(r, AppConstants.KEY_OP_TOTALES), 
                            getSafe(r, AppConstants.KEY_MAX_DRAWDOWN),
                            getSafe(r, AppConstants.KEY_ABS_DRAWDOWN), 
                            getSafe(r, AppConstants.KEY_RET_ACUMULADO), 
                            getSafe(r, AppConstants.KEY_RET_TOTAL),
                            getSafe(r, AppConstants.KEY_WIN_RATE), 
                            getSafe(r, AppConstants.KEY_PROFIT_FACTOR), 
                            getSafe(r, AppConstants.KEY_FECHA_INICIO),
                            getSafe(r, AppConstants.KEY_FECHA_FIN), 
                            getSafe(r, AppConstants.KEY_RESULTADO));
                }
            }
        } catch (IOException e) {
            log.error("Error al guardar stats: {}", e.getMessage());
        }
    }

    /**
     * Limpia los archivos de backtest antiguos si el usuario lo confirma.
     */
    public void verificarYLimpiarCarpetaEstrategia(String nombreEstrategia) {
        Path carpeta = Paths.get(PathConfig.RESULTS_DIR, nombreEstrategia);

        if (Files.exists(carpeta)) {
            log.info("\nLa carpeta de resultados '{}' ya existe.", nombreEstrategia);
            log.info("¿Deseas eliminar los archivos de BACKTEST anteriores antes de empezar? (s/n): ");

            try (Scanner sc = new Scanner(System.in)) {
                String respuesta = sc.nextLine().trim().toLowerCase();
                if (respuesta.equals("s")) {
                    limpiarArchivosBacktest(carpeta);
                } else {
                    log.info("Manteniendo archivos anteriores.");
                }
            } catch (Exception e) {
                log.error("Error al leer respuesta: {}", e.getMessage());
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
                            log.error("No se pudo borrar: {}", path.getFileName());
                        }
                    });
            log.info("Archivos de backtest antiguos eliminados.");
        } catch (IOException e) {
            log.error("Error al acceder a la carpeta: {}", e.getMessage());
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
                        stats.put(AppConstants.KEY_SYMBOL, p[0]);
                        stats.put(AppConstants.KEY_TIMEFRAME, p[1]);
                        stats.put(AppConstants.KEY_OP_GANADAS, p[2]);
                        stats.put(AppConstants.KEY_OP_PERDIDAS, p[3]);
                        stats.put(AppConstants.KEY_OP_TOTALES, p[4]);
                        stats.put(AppConstants.KEY_MAX_DRAWDOWN, p[5]);
                        stats.put(AppConstants.KEY_ABS_DRAWDOWN, p[6]);
                        stats.put(AppConstants.KEY_RET_ACUMULADO, p[7]);
                        stats.put(AppConstants.KEY_RET_TOTAL, p[8]);
                        stats.put(AppConstants.KEY_WIN_RATE, p[9]);
                        stats.put(AppConstants.KEY_PROFIT_FACTOR, p[10]);
                        stats.put(AppConstants.KEY_FECHA_INICIO, p[11]);
                        stats.put(AppConstants.KEY_FECHA_FIN, p[12]);
                        stats.put(AppConstants.KEY_RESULTADO, p[13]);
                        break;
                    }
                }
            } catch (IOException e) {
                log.error("Error al leer stats: {}", e.getMessage());
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

    /**
     * Parsea una línea CSV respetando las comillas.
     * Ejemplo: 'BTC, "100,00%", OK' -> ["BTC", "100,00%", "OK"]
     */
    private String[] parseCsvLine(String line) {
        // Regex mágica: Separa por coma SOLO si está seguida de un número par de comillas
        // (es decir, fuera de un bloque entrecomillado)
        String[] tokens = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
        
        // Limpiamos las comillas envolventes de los resultados
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
                t = t.substring(1, t.length() - 1); // Quitar comillas extremas
                t = t.replace("\"\"", "\""); // Restaurar comillas internas
            }
            tokens[i] = t;
        }
        return tokens;
    }
}
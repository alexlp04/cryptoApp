package com.bottrading.services;

import com.bottrading.exceptions.FileOperationException;
import com.bottrading.utils.AppConstants;
import com.bottrading.utils.PathConfig;
import com.bottrading.utils.SafeParser;
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
 * Servicio encargado de la persistencia de datos en el sistema de archivos
 * (CSV).
 * Gestiona el guardado de trades individuales, estadísticas agregadas y
 * limpieza de directorios.
 */
@Slf4j
@Service
public class FileService {

    private final Gson gson = new Gson();

    /**
     * Procesa y guarda solo las estadísticas del backtest.
     * Los trades ya fueron guardados por Python en streaming a CSV.
     *
     * @param nombreEstrategia Nombre de la estrategia ejecutada.
     * @param timeframe        Marco temporal utilizado.
     * @param jsonResultado    JSON con solo estadísticas devuelto por Python.
     * @throws Exception Si ocurre un error de parseo o escritura.
     */
    public void guardarEstadisticasDelBacktest(String nombreEstrategia, String timeframe, String jsonResultado)
            throws FileOperationException {

        Map<String, Object> resultado = gson.fromJson(jsonResultado, new TypeToken<Map<String, Object>>() {
        }.getType());

        String statsJson = gson.toJson(resultado.get("stats"));
        List<Map<String, Object>> statsList = gson.fromJson(statsJson, new TypeToken<List<Map<String, Object>>>() {
        }.getType());

        if (statsList != null) {
            for (Map<String, Object> stats : statsList) {
                guardarStats(nombreEstrategia, timeframe, stats, true);
            }
        }
    }

    /**
     * Procesa y guarda los resultados completos de un backtest (Trades +
     * Estadísticas).
     * NOTA: Este método está DEPRECADO. Usar guardarEstadisticasDelBacktest en su
     * lugar.
     * Los trades ahora se guardan directamente por Python en streaming.
     *
     * @param nombreEstrategia Nombre de la estrategia ejecutada.
     * @param timeframe        Marco temporal utilizado.
     * @param jsonResultado    JSON crudo devuelto por el motor de Python.
     * @throws Exception Si ocurre un error de parseo o escritura.
     */
    @Deprecated
    public void guardarResultadosCompletos(String nombreEstrategia, String timeframe, String jsonResultado)
            throws FileOperationException {

        Map<String, Object> resultado = gson.fromJson(jsonResultado, new TypeToken<Map<String, Object>>() {
        }.getType());

        String tradesJson = gson.toJson(resultado.get("trades"));
        List<Map<String, Object>> trades = gson.fromJson(tradesJson, new TypeToken<List<Map<String, Object>>>() {
        }.getType());

        String statsJson = gson.toJson(resultado.get("stats"));
        List<Map<String, Object>> statsList = gson.fromJson(statsJson, new TypeToken<List<Map<String, Object>>>() {
        }.getType());

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
     * 
     * @param timeframe  Timeframe.
     * @param symbol     Símbolo del trade.
     * @param trade      Datos del trade (precio, PnL, timestamp, etc.).
     * @param isBacktest True si es simulación, False si es tiempo real.
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
            throw new FileOperationException("Error al guardar trade: " + e.getMessage(), e);
        }
    }

    /**
     * Sobrecarga de `guardarTrade` para uso manual desde Java con tipos fuertes.
     */
    public synchronized void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            String side, BigDecimal price, long timestamp, BigDecimal pnl) {
        Map<String, Object> map = new HashMap<>();
        map.put(AppConstants.KEY_SIDE, side);
        map.put(AppConstants.KEY_PRICE, price);
        map.put(AppConstants.KEY_TIMESTAMP, timestamp);
        if (pnl != null)
            map.put(AppConstants.KEY_PNL, String.format("%.8f", pnl));

        guardarTrade(nombreEstrategia, timeframe, symbol, map, false);
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
            String currentSymbol = SafeParser.toString(stats.get(AppConstants.KEY_SYMBOL), "UNKNOWN");

            guardarOActualizarStats(filePath, stats, currentSymbol, timeframe);
            log.trace("Stats guardadas en CSV: {} [{}] {}", nombreEstrategia, currentSymbol, timeframe);

        } catch (IOException e) {
            throw new FileOperationException("Error al guardar estadísticas: " + e.getMessage(), e);
        }
    }

    /**
     * Método auxiliar para guardar o actualizar una línea de estadísticas.
     */
    private void guardarOActualizarStats(Path filePath, Map<String, Object> stats, String currentSymbol,
            String timeframe) throws IOException {
        List<String> lineas = new ArrayList<>();

        if (Files.exists(filePath)) {
            try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                String line = br.readLine();
                if (line != null) {
                    lineas.add(line);
                }
                while ((line = br.readLine()) != null) {
                    String[] p = parseCsvLine(line);
                    if (!(p.length >= 2 && p[0].equals(currentSymbol) && p[1].equals(timeframe))) {
                        lineas.add(line);
                    }
                }
            }
        } else {
            lineas.add(AppConstants.CSV_HEADER_STATS);
        }

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

            for (String line : lineas) {
                pw.println(line);
            }

            pw.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    SafeParser.toString(stats.get(AppConstants.KEY_SYMBOL), ""),
                    SafeParser.toString(stats.get(AppConstants.KEY_TIMEFRAME), ""),
                    SafeParser.toString(stats.get(AppConstants.KEY_OP_GANADAS), "0"),
                    SafeParser.toString(stats.get(AppConstants.KEY_OP_PERDIDAS), "0"),
                    SafeParser.toString(stats.get(AppConstants.KEY_OP_TOTALES), "0"),
                    SafeParser.toString(stats.get(AppConstants.KEY_MAX_DRAWDOWN), "0"),
                    SafeParser.toString(stats.get(AppConstants.KEY_ABS_DRAWDOWN), "0"),
                    SafeParser.toString(stats.get(AppConstants.KEY_RET_ACUMULADO), "0"),
                    SafeParser.toString(stats.get(AppConstants.KEY_RET_TOTAL), "0"),
                    escapeCsv(SafeParser.toString(stats.get(AppConstants.KEY_WIN_RATE), "0%")),
                    SafeParser.toString(stats.get(AppConstants.KEY_PROFIT_FACTOR), "0"),
                    SafeParser.toString(stats.get(AppConstants.KEY_FECHA_INICIO), ""),
                    SafeParser.toString(stats.get(AppConstants.KEY_FECHA_FIN), ""),
                    SafeParser.toString(stats.get(AppConstants.KEY_RESULTADO), ""));
        }
    }

    /**
     * Limpia los archivos de backtest antiguos si el usuario lo confirma.
     */
    public void verificarYLimpiarCarpetaEstrategia(String nombreEstrategia) {
        Path carpeta = Paths.get(PathConfig.RESULTS_DIR, nombreEstrategia);

        if (Files.exists(carpeta)) {
            System.out.println("La carpeta de resultados " + nombreEstrategia + " ya existe.");
            System.out.print("¿Deseas eliminar los archivos de BACKTEST anteriores antes de empezar? (s/n): ");

            Scanner sc = new Scanner(System.in);
            if (sc.hasNextLine()) {
                String respuesta = sc.nextLine().trim().toLowerCase();
                if ("s".equals(respuesta)) {
                    limpiarArchivosBacktest(carpeta);
                } else {
                    System.out.println("Manteniendo archivos anteriores.");
                }
            }
        }

    }

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
     * Lee las estadísticas actuales desde el CSV para permitir actualizaciones
     * incrementales en tiempo real.
     */
    public Map<String, Object> leerStatsActuales(String nombreEstrategia, String timeframe, String symbol) {
        Map<String, Object> stats = new HashMap<>();
        Path filePath = Paths.get(PathConfig.RESULTS_DIR, nombreEstrategia, "results.csv");

        if (Files.exists(filePath)) {
            try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                String line = br.readLine(); // ignorar header
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
                throw new FileOperationException("Error al leer estadísticas: " + e.getMessage(), e);
            }
        }
        return stats;
    }

    private Path getCarpetaEstrategia(String nombreEstrategia) throws FileOperationException {
        try {
            Path path = Paths.get(PathConfig.RESULTS_DIR, nombreEstrategia);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            return path;
        } catch (IOException e) {
            throw new FileOperationException("Error al acceder a la carpeta de la estrategia: " + e.getMessage(), e);
        }
    }

    /**
     * Parsea una línea CSV respetando las comillas.
     * Implementa un parser simple e iterativo para evitar backtracking en regex.
     */
    private String[] parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;

        while (i < line.length()) {
            char c = line.charAt(i);

            if (handleQuoteCharacter(c, i, line, inQuotes, current)) {
                if (line.charAt(i) == '\"' && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    i++; // Salta comilla doble
                }
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(trimQuotedString(current.toString()));
                current = new StringBuilder();
            } else {
                current.append(c);
            }
            i++;
        }

        tokens.add(trimQuotedString(current.toString()));
        return tokens.toArray(new String[0]);
    }

    /**
     * Determina si el carácter es una comilla válida para procesar.
     */
    private boolean handleQuoteCharacter(char c, int index, String line, boolean inQuotes, StringBuilder current) {
        if (c != '\"') {
            return false;
        }
        if (inQuotes && index + 1 < line.length() && line.charAt(index + 1) == '\"') {
            current.append('\"');
            return true;
        }
        return !inQuotes && current.toString().trim().isEmpty();
    }

    /**
     * Limpia una cadena entrecomillada.
     */
    private String trimQuotedString(String token) {
        token = token.trim();
        if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) {
            token = token.substring(1, token.length() - 1);
        }
        return token;
    }

    /**
     * Escapa un valor para CSV. Si el valor contiene comas, comillas o saltos de
     * línea,
     * lo envuelve en comillas dobles para que no rompa la estructura de columnas.
     */
    private String escapeCsv(String data) {
        if (data == null)
            return "";
        if (data.contains(",") || data.contains("\"") || data.contains("\n")) {
            // Reemplazamos las comillas internas por dobles comillas (estándar CSV)
            data = data.replace("\"", "\"\"");
            return "\"" + data + "\"";
        }
        return data;
    }
}
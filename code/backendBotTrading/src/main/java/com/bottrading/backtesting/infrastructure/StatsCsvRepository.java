package com.bottrading.backtesting.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bottrading.shared.exceptions.FileOperationException;
import com.bottrading.shared.utils.AppConstants;
import com.bottrading.shared.utils.SafeParser;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import lombok.extern.slf4j.Slf4j;

/**
 * Repositorio para persistencia y lectura de estadísticas de estrategias.
 * Maneja results.csv y parseo de JSON a CSV.
 * 
 * Responsabilidad única: CRUD de estadísticas en CSV.
 */
@Slf4j
@Service
public class StatsCsvRepository {

    private final Gson gson = new Gson();
    private final CsvUtilities csvUtils;

    public StatsCsvRepository(CsvUtilities csvUtils) {
        this.csvUtils = csvUtils;
    }

    /**
     * Procesa y guarda solo las estadísticas del backtest desde JSON.
     * Los trades ya fueron guardados por Python en streaming.
     *
     * @param nombreEstrategia Nombre de la estrategia ejecutada.
     * @param timeframe        Marco temporal utilizado.
     * @param jsonResultado    JSON con solo estadísticas devuelto por Python.
     */
    public void guardarEstadisticasDelBacktest(String nombreEstrategia, String timeframe, String jsonResultado)
            throws FileOperationException {
        try {
            Map<String, Object> resultado = gson.fromJson(jsonResultado,
                    new TypeToken<Map<String, Object>>() {}.getType());

            String statsJson = gson.toJson(resultado.get("stats"));
            List<Map<String, Object>> statsList = gson.fromJson(statsJson,
                    new TypeToken<List<Map<String, Object>>>() {}.getType());

            if (statsList != null) {
                for (Map<String, Object> stats : statsList) {
                    guardarStats(nombreEstrategia, timeframe, stats, true);
                }
            }
        } catch (Exception e) {
            throw new FileOperationException("Error al procesar JSON de estadísticas: " + e.getMessage(), e);
        }
    }

    /**
     * Actualiza el archivo de estadísticas acumuladas (results.csv).
     * Si ya existe una entrada para ese símbolo y timeframe, la sobrescribe.
     *
     * @param nombreEstrategia Nombre de la estrategia.
     * @param timeframe        Timeframe.
     * @param stats            Mapa con las métricas.
     * @param isBacktest       True si es simulación.
     */
    public synchronized void guardarStats(String nombreEstrategia, String timeframe, Map<String, Object> stats,
            boolean isBacktest) {
        try {
            String suffix = isBacktest ? "_backtest.csv" : ".csv";
            Path filePath = csvUtils.getCarpetaEstrategia(nombreEstrategia).resolve("results" + suffix);
            String currentSymbol = SafeParser.toString(stats.get(AppConstants.KEY_SYMBOL), "UNKNOWN");

            guardarOActualizarStats(filePath, stats, currentSymbol, timeframe);
            log.trace("Stats guardadas: {} [{}] {}", nombreEstrategia, currentSymbol, timeframe);

        } catch (IOException e) {
            throw new FileOperationException("Error al guardar estadísticas: " + e.getMessage(), e);
        }
    }

    /**
     * Lee las estadísticas actuales desde el CSV.
     * Permite actualizaciones incrementales en tiempo real.
     */
    public Map<String, Object> leerStatsActuales(String nombreEstrategia, String timeframe, String symbol) {
        Map<String, Object> stats = new HashMap<>();
        Path filePath = Paths.get(com.bottrading.shared.utils.PathConfig.RESULTS_DIR, nombreEstrategia, "results.csv");

        if (!Files.exists(filePath)) {
            return stats;
        }

        try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            br.readLine();  // ignorar header
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = csvUtils.parseCsvLine(line);
                if (parts.length >= 14 && parts[0].equals(symbol) && parts[1].equals(timeframe)) {
                    return parseStatsLine(parts);
                }
            }
        } catch (IOException e) {
            throw new FileOperationException("Error al leer estadísticas: " + e.getMessage(), e);
        }
        return stats;
    }


    private void guardarOActualizarStats(Path filePath, Map<String, Object> stats, String currentSymbol,
            String timeframe) throws IOException {
        List<String> lineas = cargarLineasExistentes(filePath, currentSymbol, timeframe);

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

            for (String line : lineas) {
                pw.println(line);
            }

            // Escribir nueva línea
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
                    csvUtils.escapeCsv(SafeParser.toString(stats.get(AppConstants.KEY_WIN_RATE), "0%")),
                    SafeParser.toString(stats.get(AppConstants.KEY_PROFIT_FACTOR), "0"),
                    SafeParser.toString(stats.get(AppConstants.KEY_FECHA_INICIO), ""),
                    SafeParser.toString(stats.get(AppConstants.KEY_FECHA_FIN), ""),
                    SafeParser.toString(stats.get(AppConstants.KEY_RESULTADO), ""));
        }
    }

    private List<String> cargarLineasExistentes(Path filePath, String currentSymbol, String timeframe)
            throws IOException {
        List<String> lineas = new ArrayList<>();

        if (Files.exists(filePath)) {
            try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                String line = br.readLine();
                if (line != null) {
                    lineas.add(line);  // Header
                }
                while ((line = br.readLine()) != null) {
                    String[] parts = csvUtils.parseCsvLine(line);
                    if (!(parts.length >= 2 && parts[0].equals(currentSymbol) && parts[1].equals(timeframe))) {
                        lineas.add(line);
                    }
                }
            }
        } else {
            lineas.add(AppConstants.CSV_HEADER_STATS);
        }

        return lineas;
    }

    private Map<String, Object> parseStatsLine(String[] parts) {
        Map<String, Object> stats = new HashMap<>();
        stats.put(AppConstants.KEY_SYMBOL, parts[0]);
        stats.put(AppConstants.KEY_TIMEFRAME, parts[1]);
        stats.put(AppConstants.KEY_OP_GANADAS, parts[2]);
        stats.put(AppConstants.KEY_OP_PERDIDAS, parts[3]);
        stats.put(AppConstants.KEY_OP_TOTALES, parts[4]);
        stats.put(AppConstants.KEY_MAX_DRAWDOWN, parts[5]);
        stats.put(AppConstants.KEY_ABS_DRAWDOWN, parts[6]);
        stats.put(AppConstants.KEY_RET_ACUMULADO, parts[7]);
        stats.put(AppConstants.KEY_RET_TOTAL, parts[8]);
        stats.put(AppConstants.KEY_WIN_RATE, parts[9]);
        stats.put(AppConstants.KEY_PROFIT_FACTOR, parts[10]);
        stats.put(AppConstants.KEY_FECHA_INICIO, parts[11]);
        stats.put(AppConstants.KEY_FECHA_FIN, parts[12]);
        stats.put(AppConstants.KEY_RESULTADO, parts[13]);
        return stats;
    }
}

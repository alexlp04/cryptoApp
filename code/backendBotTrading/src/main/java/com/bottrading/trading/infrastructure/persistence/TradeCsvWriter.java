package com.bottrading.trading.infrastructure.persistence;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bottrading.shared.exceptions.FileOperationException;
import com.bottrading.shared.utils.AppConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * Adaptador para persistencia de trades en CSV.
 * Escribe trades individuales en archivos CSV con headers automáticos.
 * 
 * Responsabilidad única: escritura de trades a CSV.
 */
@Slf4j
@Service
public class TradeCsvWriter {

    private final TradingCsvUtilities csvUtils;

    public TradeCsvWriter(TradingCsvUtilities csvUtils) {
        this.csvUtils = csvUtils;
    }

    /**
     * Guarda un trade individual en su archivo CSV correspondiente.
     * Si el archivo no existe, crea la cabecera automáticamente.
     *
     * @param nombreEstrategia Nombre de la estrategia.
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
            Path filePath = csvUtils.getCarpetaEstrategia(nombreEstrategia).resolve(fileName);

            boolean isNew = !Files.exists(filePath);

            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {

                if (isNew) {
                    pw.println(AppConstants.CSV_HEADER_TRADES);
                    log.debug("Creado nuevo archivo de trades: {}", fileName);
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
     * Sobrecarga para guardar trade con tipos fuertes.
     */
    public synchronized void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            String side, BigDecimal price, long timestamp, BigDecimal pnl) {
        Map<String, Object> map = Map.of(
                AppConstants.KEY_SIDE, side,
                AppConstants.KEY_PRICE, price,
                AppConstants.KEY_TIMESTAMP, timestamp,
                AppConstants.KEY_PNL, pnl != null ? String.format("%.8f", pnl) : ""
        );
        guardarTrade(nombreEstrategia, timeframe, symbol, map, false);
    }
}

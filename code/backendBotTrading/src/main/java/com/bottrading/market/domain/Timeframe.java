package com.bottrading.market.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Vocabulario unico de timeframes soportados.
 *
 * <p>Reune las tablas que antes estaban duplicadas en FetchService (milisegundos),
 * StrategyInspector / AITrainingService / AIOptimizationService (velas por dia) y
 * TrainCommand / OptimizeCommand (politica de dias de historico).
 *
 * <p>Los metodos {@code *OrDefault} existen porque los llamantes historicos no
 * comparten el mismo comportamiento ante un timeframe desconocido: unos lanzan y
 * otros caen a un valor por defecto. Cada uno conserva el suyo.
 */
public enum Timeframe {

    M1("1m", 60_000L, 1440, 180, 30),
    M5("5m", 300_000L, 288, 365, 90),
    M15("15m", 900_000L, 96, 730, 180),
    H1("1h", 3_600_000L, 24, 1095, 365),
    H4("4h", 14_400_000L, 6, 1825, 730),
    D1("1d", 86_400_000L, 1, 3650, 1095);

    private static final Map<String, Timeframe> POR_CODIGO = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Timeframe::codigo, Function.identity()));

    /** Timeframe cuyos valores se usan como fallback donde antes habia un {@code default ->}. */
    private static final Timeframe FALLBACK = M5;

    private final String codigo;
    private final long millis;
    private final int velasPorDia;
    private final int maxDiasEntrenamiento;
    private final int diasEntrenamientoPorDefecto;

    Timeframe(String codigo, long millis, int velasPorDia, int maxDiasEntrenamiento, int diasEntrenamientoPorDefecto) {
        this.codigo = codigo;
        this.millis = millis;
        this.velasPorDia = velasPorDia;
        this.maxDiasEntrenamiento = maxDiasEntrenamiento;
        this.diasEntrenamientoPorDefecto = diasEntrenamientoPorDefecto;
    }

    public String codigo() {
        return codigo;
    }

    public long millis() {
        return millis;
    }

    public int velasPorDia() {
        return velasPorDia;
    }

    public int maxDiasEntrenamiento() {
        return maxDiasEntrenamiento;
    }

    public int diasEntrenamientoPorDefecto() {
        return diasEntrenamientoPorDefecto;
    }

    /** Busca sin distinguir mayusculas; vacio si el codigo no esta soportado. */
    public static Optional<Timeframe> buscar(String codigo) {
        if (codigo == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(POR_CODIGO.get(codigo.toLowerCase()));
    }

    /** Igual que {@link #buscar} pero falla ante un timeframe desconocido. */
    public static Timeframe parse(String codigo) {
        return buscar(codigo).orElseThrow(
                () -> new IllegalArgumentException("Timeframe no soportado: " + codigo));
    }

    /** Duracion en milisegundos, o {@code porDefecto} si el codigo no se reconoce. */
    public static long millisOrDefault(String codigo, long porDefecto) {
        return buscar(codigo).map(Timeframe::millis).orElse(porDefecto);
    }

    /** Velas por dia, cayendo a las de 5m (288) ante un timeframe desconocido. */
    public static int velasPorDiaOrDefault(String codigo) {
        return buscar(codigo).orElse(FALLBACK).velasPorDia();
    }

    /** Politica de historico, cayendo a la de 5m ante un timeframe desconocido. */
    public static Timeframe politicaEntrenamiento(String codigo) {
        return buscar(codigo).orElse(FALLBACK);
    }
}

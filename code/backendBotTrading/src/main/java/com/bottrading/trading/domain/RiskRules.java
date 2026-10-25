package com.bottrading.trading.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Reglas de riesgo de una plataforma de trading, las mismas que aplica el backtest en Python
 * (risk_management.py):
 * <ul>
 *   <li>Riesgo fijo por operación: se arriesga {@code riesgoPorTrade} del equity y el tamaño sale de la
 *       distancia al stop: {@code unidades = equity * riesgoPorTrade / (entrada - stop)}.</li>
 *   <li>Un corto se dimensiona igual, con el stop por encima de la entrada; se simula sin apalancamiento.</li>
 *   <li>Tope por posición: una posición no supera {@link #MAX_POSITION_FRACTION} del equity ni lo que
 *       queda reservado (spot, sin apalancamiento).</li>
 *   <li>Riesgo abierto total: lo arriesgado en las posiciones abiertas no supera
 *       {@link #MAX_OPEN_RISK_FRACTION} del equity.</li>
 * </ul>
 */
public final class RiskRules {

    public static final BigDecimal MAX_POSITION_FRACTION = new BigDecimal("0.25");
    public static final BigDecimal MAX_OPEN_RISK_FRACTION = new BigDecimal("0.06");

    private static final int SCALE = 8;

    private RiskRules() {
    }

    /** Tamaño de una posición: unidades, nocional en dinero y pérdida si salta el stop. */
    public record Sizing(BigDecimal unidades, BigDecimal nocional, BigDecimal riesgo) {
    }

    /** Un stop de una posición larga debe ser positivo y estar por debajo de la entrada. */
    public static boolean stopValido(BigDecimal entrada, BigDecimal stop) {
        return stopValido(entrada, stop, Lado.LONG);
    }

    /** El stop de un largo está por debajo de la entrada; el de un corto, por encima. */
    public static boolean stopValido(BigDecimal entrada, BigDecimal stop, Lado lado) {
        if (stop == null || entrada == null || stop.signum() <= 0) {
            return false;
        }
        return lado == Lado.SHORT ? stop.compareTo(entrada) > 0 : stop.compareTo(entrada) < 0;
    }

    /**
     * Dimensiona la posición, o devuelve {@code null} si no se puede: stop inválido, equity o disponible
     * no positivos.
     *
     * @param equity        capital de la estrategia (reservado + comprometido)
     * @param riesgoPorTrade fracción del equity que se acepta perder si salta el stop
     * @param entrada       precio de entrada (ya con slippage)
     * @param stop          precio del stop
     * @param disponible    capital reservado que aún no está en posiciones
     */
    public static Sizing dimensionar(BigDecimal equity, BigDecimal riesgoPorTrade, BigDecimal entrada,
            BigDecimal stop, BigDecimal disponible) {
        return dimensionar(equity, riesgoPorTrade, entrada, stop, disponible, Lado.LONG);
    }

    /** Como {@link #dimensionar(BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal)}, para largos o cortos. */
    public static Sizing dimensionar(BigDecimal equity, BigDecimal riesgoPorTrade, BigDecimal entrada,
            BigDecimal stop, BigDecimal disponible, Lado lado) {
        if (equity == null || equity.signum() <= 0 || riesgoPorTrade == null || riesgoPorTrade.signum() <= 0
                || disponible == null || disponible.signum() <= 0 || !stopValido(entrada, stop, lado)) {
            return null;
        }
        BigDecimal porRiesgo = equity.multiply(riesgoPorTrade).divide(entrada.subtract(stop).abs(), SCALE * 2,
                RoundingMode.DOWN);
        BigDecimal tope = equity.multiply(MAX_POSITION_FRACTION).min(disponible);
        BigDecimal porTope = tope.divide(entrada, SCALE * 2, RoundingMode.DOWN);
        BigDecimal unidades = porRiesgo.min(porTope).setScale(SCALE, RoundingMode.DOWN);
        if (unidades.signum() <= 0) {
            return null;
        }
        BigDecimal nocional = unidades.multiply(entrada).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal riesgo = unidades.multiply(entrada.subtract(stop).abs()).setScale(SCALE, RoundingMode.HALF_UP);
        return new Sizing(unidades, nocional, riesgo);
    }

    /** ¿Abrir esta posición dejaría más riesgo abierto del permitido? */
    public static boolean superaRiesgoAbierto(BigDecimal equity, BigDecimal riesgoAbierto, BigDecimal riesgoNuevo) {
        BigDecimal limite = equity.multiply(MAX_OPEN_RISK_FRACTION);
        return riesgoAbierto.add(riesgoNuevo).compareTo(limite) > 0;
    }
}

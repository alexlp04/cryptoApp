package com.bottrading.trading.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Costes de ejecución del simulador, iguales a los del backtest (backtest_engine.py): una orden a mercado
 * se llena algo peor que el precio de la señal (slippage) y cada lado paga comisión. Un objetivo es una
 * orden límite: se llena a su precio, sin slippage.
 *
 * <p>shortcut: constantes y no propiedades; pasarlas a configuración cuando haya más de un exchange o
 * tarifas distintas por usuario.
 */
public final class ExecutionCosts {

    /** Comisión por lado (Binance spot: 0,1 %). */
    public static final BigDecimal FEE_RATE = new BigDecimal("0.001");
    /** Deslizamiento adverso de una orden a mercado (0,05 %). */
    public static final BigDecimal SLIPPAGE = new BigDecimal("0.0005");

    private static final int SCALE = 8;

    private ExecutionCosts() {
    }

    public static BigDecimal precioCompra(BigDecimal precio) {
        return precio.multiply(BigDecimal.ONE.add(SLIPPAGE)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** @param ordenLimite true para un objetivo (se llena a su precio); false para venta a mercado/stop */
    public static BigDecimal precioVenta(BigDecimal precio, boolean ordenLimite) {
        if (ordenLimite) {
            return precio.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return precio.multiply(BigDecimal.ONE.subtract(SLIPPAGE)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Comisión total de la operación: tasa * unidades * (entrada + salida). */
    public static BigDecimal comision(BigDecimal unidades, BigDecimal entrada, BigDecimal salida) {
        return FEE_RATE.multiply(unidades).multiply(entrada.add(salida)).setScale(SCALE, RoundingMode.HALF_UP);
    }
}

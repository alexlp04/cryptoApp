package com.bottrading.interfaces.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Lo que la API de solo lectura devuelve: copias planas de las entidades, sin exponer su modelo JPA. */
public final class ApiViews {

    private ApiViews() {
    }

    public record Health(String status) {
    }

    /** Estado detallado: ACTIVAS en base de datos frente a las que tienen proceso vivo. */
    public record Status(String status, List<Long> activas, List<Long> sinProceso) {
    }

    public record Strategy(Long id, String nombre, String modelo, String estado, String timeframe, List<String> simbolos,
            BigDecimal capitalAsignado, BigDecimal capitalReservado, BigDecimal capitalComprometido,
            BigDecimal riesgoAbierto, BigDecimal riskPerTrade, boolean real, Long usuarioId, Long walletId,
            long posicionesAbiertas) {
    }

    public record Position(Long id, String simbolo, String lado, boolean abierta, BigDecimal precioEntrada,
            BigDecimal unidades, BigDecimal margen, BigDecimal stopLoss, BigDecimal takeProfit, BigDecimal riesgo,
            BigDecimal precioSalida, BigDecimal pnl, BigDecimal comisiones, Instant apertura, Instant cierre) {
    }

    public record LedgerLine(Long id, Long walletId, Long estrategiaId, String tipo, BigDecimal importe,
            BigDecimal saldoTras, String referencia, Instant fecha) {
    }

    public record Wallet(Long id, String nombre, Long usuarioId, String tipo, boolean activa, BigDecimal balanceReal,
            BigDecimal balanceDisponible) {
    }

    /** Un punto de la curva de resultados: PnL neto de una operación cerrada y el acumulado hasta ella. */
    public record PnlPoint(Instant cierre, BigDecimal pnl, BigDecimal acumulado) {
    }

    public record Pnl(long operaciones, long ganadas, long perdidas, BigDecimal pnlTotal, BigDecimal comisiones,
            List<PnlPoint> serie) {
    }
}

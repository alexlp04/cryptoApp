package com.bottrading.interfaces.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bottrading.interfaces.api.ApiViews.LedgerLine;
import com.bottrading.interfaces.api.ApiViews.Pnl;
import com.bottrading.interfaces.api.ApiViews.PnlPoint;
import com.bottrading.interfaces.api.ApiViews.Position;
import com.bottrading.interfaces.api.ApiViews.Status;
import com.bottrading.interfaces.api.ApiViews.Strategy;
import com.bottrading.interfaces.api.ApiViews.Wallet;
import com.bottrading.strategy.application.port.in.StrategyLifecycleUseCase;
import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;
import com.bottrading.trading.domain.LedgerEntry;
import com.bottrading.trading.domain.LedgerRepository;
import com.bottrading.trading.domain.Posicion;
import com.bottrading.trading.domain.PosicionRepository;
import com.bottrading.wallet.infrastructure.persistence.WalletRepository;

import lombok.RequiredArgsConstructor;

/** Consultas de solo lectura para la API: todo es lectura y se devuelve ya aplanado en {@link ApiViews}. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApiQueryService {

    static final int MAX_LIMIT = 500;

    private final InstanciaEstrategiaRepository instanciaRepo;
    private final PosicionRepository posicionRepo;
    private final LedgerRepository ledgerRepo;
    private final WalletRepository walletRepo;
    private final StrategyLifecycleUseCase lifecycle;

    /** UP si cada estrategia ACTIVA tiene su proceso vivo; DOWN si alguna se quedó sin él. */
    public Status status() {
        Set<Long> vivas = lifecycle.idsConProceso();
        List<Long> activas = instanciaRepo.findByEstado(EstadoEstrategia.ACTIVA).stream()
                .map(InstanciaEstrategia::getId).toList();
        List<Long> sinProceso = activas.stream().filter(id -> !vivas.contains(id)).toList();
        return new Status(sinProceso.isEmpty() ? "UP" : "DOWN", activas, sinProceso);
    }

    public List<Strategy> strategies() {
        return instanciaRepo.findAll(Sort.by(Sort.Direction.DESC, "id")).stream().map(this::toView).toList();
    }

    public boolean exists(Long strategyId) {
        return instanciaRepo.existsById(strategyId);
    }

    public List<Position> positions(Long strategyId, boolean abiertas, int limit) {
        return posicionRepo.findByInstanciaIdAndAbiertaOrderByIdDesc(strategyId, abiertas, page(limit)).stream()
                .map(ApiQueryService::toView).toList();
    }

    public List<LedgerLine> ledger(Long strategyId, int limit) {
        return ledgerRepo.findByEstrategiaIdOrderByIdDesc(strategyId, page(limit)).stream()
                .map(ApiQueryService::toView).toList();
    }

    public List<Wallet> wallets() {
        return walletRepo.findAll(Sort.by("id")).stream().map(ApiQueryService::toView).toList();
    }

    /** Resultados netos de las operaciones cerradas, con la serie acumulada para dibujar la curva. */
    public Pnl pnl(Long strategyId) {
        List<Posicion> cerradas = posicionRepo.findByInstanciaIdAndAbiertaFalseOrderByFechaCierreAsc(strategyId);
        BigDecimal acumulado = BigDecimal.ZERO;
        BigDecimal comisiones = BigDecimal.ZERO;
        long ganadas = 0;
        long perdidas = 0;
        List<PnlPoint> serie = new ArrayList<>(cerradas.size());
        for (Posicion p : cerradas) {
            BigDecimal pnl = Optional.ofNullable(p.getPnl()).orElse(BigDecimal.ZERO);
            acumulado = acumulado.add(pnl);
            comisiones = comisiones.add(Optional.ofNullable(p.getComisiones()).orElse(BigDecimal.ZERO));
            if (pnl.signum() > 0) {
                ganadas++;
            } else if (pnl.signum() < 0) {
                perdidas++;
            }
            serie.add(new PnlPoint(p.getFechaCierre(), pnl, acumulado));
        }
        return new Pnl(cerradas.size(), ganadas, perdidas, acumulado, comisiones, serie);
    }

    /** Límite pedido, acotado a 1..{@value #MAX_LIMIT}. */
    static PageRequest page(int limit) {
        return PageRequest.of(0, Math.max(1, Math.min(limit, MAX_LIMIT)));
    }

    private Strategy toView(InstanciaEstrategia i) {
        return new Strategy(i.getId(), i.getNombreEstrategia(), i.getNombreModelo(), i.getEstado().name(),
                i.getTimeframe(), i.getSimbolos(), i.getCapitalAsignado(), i.getCapitalReservado(),
                i.getCapitalComprometido(), i.getRiesgoAbierto(), i.getRiskPerTrade(), i.isEsReal(),
                i.getUsuarioId(), i.getWalletAsociada(), posicionRepo.countByInstanciaIdAndAbiertaTrue(i.getId()));
    }

    private static Position toView(Posicion p) {
        return new Position(p.getId(), p.getSimbolo(), p.getLado().name(), p.isAbierta(), p.getPrecioEntrada(),
                p.getUnidades(), p.getMargenInvertido(), p.getStopLoss(), p.getTakeProfit(), p.getRiesgo(),
                p.getPrecioSalida(), p.getPnl(), p.getComisiones(), p.getFechaCreacion(), p.getFechaCierre());
    }

    private static LedgerLine toView(LedgerEntry e) {
        return new LedgerLine(e.getId(), e.getWalletId(), e.getEstrategiaId(), e.getType().name(), e.getAmount(),
                e.getBalanceAfter(), e.getReference(), e.getTimestamp());
    }

    private static Wallet toView(com.bottrading.wallet.domain.Wallet w) {
        return new Wallet(w.getId(), w.getNombre(), w.getUsuarioId(), w.getType().name(), w.isActive(),
                w.getBalanceReal(), w.getBalanceDisponible());
    }
}

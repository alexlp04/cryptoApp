package com.bottrading.interfaces.api;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.bottrading.interfaces.api.ApiViews.Health;
import com.bottrading.interfaces.api.ApiViews.LedgerLine;
import com.bottrading.interfaces.api.ApiViews.Pnl;
import com.bottrading.interfaces.api.ApiViews.Position;
import com.bottrading.interfaces.api.ApiViews.Status;
import com.bottrading.interfaces.api.ApiViews.Strategy;
import com.bottrading.interfaces.api.ApiViews.Wallet;

import lombok.RequiredArgsConstructor;

/**
 * API REST de solo lectura para la UI y el móvil. Solo GET: no hay ninguna operación que cambie estado.
 * Con un único token todo es visible, también las estrategias de todos los usuarios de la instalación.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ApiController {

    private final ApiQueryService queries;

    /** Sin autenticación (HEALTHCHECK de Docker): solo UP o DOWN, con 503 si falla algo o falta un proceso. */
    @GetMapping("/health")
    public ResponseEntity<Health> health() {
        String status;
        try {
            status = queries.status().status();
        } catch (RuntimeException e) { // base de datos caída: la salud es DOWN, no un 500
            status = "DOWN";
        }
        HttpStatus http = "UP".equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(http).body(new Health(status));
    }

    @GetMapping("/status")
    public Status status() {
        return queries.status();
    }

    @GetMapping("/strategies")
    public List<Strategy> strategies() {
        return queries.strategies();
    }

    /** {@code state=open} (por defecto) o {@code closed}. */
    @GetMapping("/strategies/{id}/positions")
    public List<Position> positions(@PathVariable Long id, @RequestParam(defaultValue = "open") String state,
            @RequestParam(defaultValue = "100") int limit) {
        requireStrategy(id);
        if (!"open".equals(state) && !"closed".equals(state)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "state debe ser open o closed");
        }
        return queries.positions(id, "open".equals(state), limit);
    }

    @GetMapping("/strategies/{id}/pnl")
    public Pnl pnl(@PathVariable Long id) {
        requireStrategy(id);
        return queries.pnl(id);
    }

    @GetMapping("/strategies/{id}/ledger")
    public List<LedgerLine> ledger(@PathVariable Long id, @RequestParam(defaultValue = "100") int limit) {
        requireStrategy(id);
        return queries.ledger(id, limit);
    }

    @GetMapping("/wallets")
    public List<Wallet> wallets() {
        return queries.wallets();
    }

    private void requireStrategy(Long id) {
        if (!queries.exists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "estrategia " + id + " no encontrada");
        }
    }
}

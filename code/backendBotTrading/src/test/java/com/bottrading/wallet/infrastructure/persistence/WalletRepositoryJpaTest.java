package com.bottrading.wallet.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.bottrading.wallet.domain.Wallet;
import com.bottrading.wallet.domain.WalletType;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("schema")
class WalletRepositoryJpaTest {

    @Autowired
    private WalletRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    private Wallet wallet(long usuarioId, String nombre) {
        Wallet w = new Wallet();
        w.setNombre(nombre);
        w.setUsuarioId(usuarioId);
        w.setBalanceReal(new BigDecimal("100"));
        w.setBalanceDisponible(new BigDecimal("100"));
        w.setType(WalletType.PAPER);
        w.setActive(false);
        return w;
    }

    @Test
    @DisplayName("Dos wallets con el mismo nombre para un usuario violan la restricción única (#95)")
    void duplicate_wallet_name_per_user_is_rejected() {
        jdbc.update("INSERT INTO usuario (nombre, password_hash, eliminado) VALUES ('u-wallet', 'x', 0)");
        Long usuarioId = jdbc.queryForObject("SELECT id FROM usuario WHERE nombre = 'u-wallet'", Long.class);

        repo.saveAndFlush(wallet(usuarioId, "principal"));

        assertThrows(DataIntegrityViolationException.class, () -> repo.saveAndFlush(wallet(usuarioId, "principal")));
    }
}

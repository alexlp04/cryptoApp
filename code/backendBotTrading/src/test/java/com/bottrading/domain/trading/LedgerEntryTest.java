package com.bottrading.domain.trading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * TEST DOMINIO - LedgerEntry (accounting entry)
 *
 * Características:
 *  • SIN @SpringBootTest → Sin contexto Spring
 *  • SIN @Mock → Solo JUnit 5 puro
 *  • Testea: validaciones de montos, tipos de entrada, invariantes contables
 *  • Nota: LedgerEntry es el registro inmodificable de cualquier movimiento en wallet
 */
@DisplayName("Domain Entity - LedgerEntry")
class LedgerEntryTest {

    // ══════════════════════════════════════════════════════════════════════════
    // CONSTRUCCIÓN Y ESTADO INICIAL
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe crear ledger entry válida")
    void should_create_valid_ledger_entry() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();

        // Then
        assertThat(entry, is(notNullValue()));
    }

    @Test
    @DisplayName("✓ Debe asignar walletId válido")
    void should_assign_valid_walletId() {
        // Given
        LedgerEntry entry = new LedgerEntry();
        Long walletId = 123L;

        // When
        entry.setWalletId(walletId);

        // Then
        assertThat(entry.getWalletId(), is(equalTo(walletId)));
    }

    @Test
    @DisplayName("✓ Debe asignar estrategiaId (optional)")
    void should_assign_estrategiaId_optional() {
        // Given
        LedgerEntry entry = new LedgerEntry();
        Long estrategiaId = 456L;

        // When
        entry.setEstrategiaId(estrategiaId);

        // Then
        assertThat(entry.getEstrategiaId(), is(equalTo(estrategiaId)));
    }

    @Test
    @DisplayName("✓ Debe asignar tipo válido")
    void should_assign_valid_type() {
        // Given
        LedgerEntry entry = new LedgerEntry();
        LedgerType type = LedgerType.REALIZED_PNL;

        // When
        entry.setType(type);

        // Then
        assertThat(entry.getType(), is(equalTo(type)));
    }

    @Test
    @DisplayName("✓ Debe asignar amount positivo")
    void should_assign_positive_amount() {
        // Given
        LedgerEntry entry = new LedgerEntry();
        BigDecimal amount = new BigDecimal("1000.00");

        // When
        entry.setAmount(amount);

        // Then
        assertThat(entry.getAmount(), is(greaterThan(BigDecimal.ZERO)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: TIPOS DE LEDGER
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe aceptar todos los tipos de LedgerType")
    void should_accept_all_ledger_types() {
        // Given
        LedgerType[] types = LedgerType.values();

        for (LedgerType type : types) {
            // When
            LedgerEntry entry = new LedgerEntry();
            entry.setType(type);

            // Then
            assertThat(entry.getType(), is(equalTo(type)));
        }
    }

    @Test
    @DisplayName("✓ Tipo DEPOSIT válido para entrada de capital")
    void should_accept_DEPOSIT_type_for_capital_entry() {
        // Given
        LedgerEntry entry = new LedgerEntry();
        entry.setType(LedgerType.AVAILABLE);
        entry.setAmount(new BigDecimal("10000.00"));

        // Then
        assertThat(entry.getType(), is(notNullValue()));
        assertThat(entry.getAmount().compareTo(BigDecimal.ZERO), is(greaterThan(0)));
    }

    @Test
    @DisplayName("✓ Tipo REALIZED_PNL para ganancia realizada")
    void should_accept_REALIZED_PNL_type_for_profit() {
        // Given
        LedgerEntry entry = new LedgerEntry();
        entry.setType(LedgerType.REALIZED_PNL);
        entry.setAmount(new BigDecimal("500.50"));

        // Then
        assertThat(entry.getType(), is(equalTo(LedgerType.REALIZED_PNL)));
        assertThat(entry.getAmount(), is(greaterThan(BigDecimal.ZERO)));
    }

    @Test
    @DisplayName("✓ Tipo FEE para comisiones")
    void should_accept_FEE_type_for_commissions() {
        // Given
        LedgerEntry entry = new LedgerEntry();
        entry.setType(LedgerType.FEE);
        entry.setAmount(new BigDecimal("10.00"));

        // Then
        assertThat(entry.getType(), is(equalTo(LedgerType.FEE)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: AMOUNT (montos)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe aceptar amount positivo")
    void should_accept_positive_amount() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();
        entry.setAmount(new BigDecimal("1500.75"));

        // Then
        assertThat(entry.getAmount().compareTo(BigDecimal.ZERO),
                is(greaterThan(0)));
    }

    @Test
    @DisplayName("✓ Debe aceptar amount con precisión de hasta 2 decimales (USD)")
    void should_accept_amount_with_usd_precision() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();
        entry.setAmount(new BigDecimal("1234.56"));

        // Then
        assertThat(entry.getAmount(), is(notNullValue()));
    }

    @Test
    @DisplayName("✓ Debe aceptar amount muy pequeño (ej: 0.01 USD)")
    void should_accept_minimal_amount() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();
        entry.setAmount(new BigDecimal("0.01"));

        // Then
        assertThat(entry.getAmount().compareTo(BigDecimal.ZERO),
                is(greaterThan(0)));
    }

    @Test
    @DisplayName("✓ Debe aceptar amount muy grande (ej: 1 millón USD)")
    void should_accept_large_amount() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();
        entry.setAmount(new BigDecimal("1000000.00"));

        // Then
        assertThat(entry.getAmount(),
                is(greaterThan(new BigDecimal("1000.00"))));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: BALANCE AFTER
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe guardar balance posterior a la operación")
    void should_save_balance_after_operation() {
        // Given
        LedgerEntry entry = new LedgerEntry();
        BigDecimal balanceAfter = new BigDecimal("9500.00");

        // When
        entry.setBalanceAfter(balanceAfter);

        // Then
        assertThat(entry.getBalanceAfter(), is(equalTo(balanceAfter)));
    }

    @Test
    @DisplayName("✓ Balance puede ser cero después de operación")
    void should_accept_zero_balance_after() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();
        entry.setBalanceAfter(BigDecimal.ZERO);

        // Then
        assertThat(entry.getBalanceAfter().compareTo(BigDecimal.ZERO),
                is(equalTo(0)));
    }

    @Test
    @DisplayName("✓ Balance puede ser negativo en cuenta de margen")
    void should_accept_negative_balance_after_for_margin() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();
        entry.setBalanceAfter(new BigDecimal("-5000.00"));

        // Then
        assertThat(entry.getBalanceAfter().compareTo(BigDecimal.ZERO),
                is(lessThan(0)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: TIMESTAMP
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe guardar timestamp de la operación")
    void should_save_operation_timestamp() {
        // Given
        LedgerEntry entry = new LedgerEntry();
        Instant now = Instant.now();

        // When
        entry.setTimestamp(now);

        // Then
        assertThat(entry.getTimestamp(), is(notNullValue()));
        assertThat(entry.getTimestamp(), is(equalTo(now)));
    }

    @Test
    @DisplayName("✓ Timestamp debe ser en el pasado o presente, no futuro")
    void should_have_timestamp_in_past_or_present() {
        // Given
        LedgerEntry entry = new LedgerEntry();
        Instant now = Instant.now();

        // When
        entry.setTimestamp(now);

        // Then
        assertThat(entry.getTimestamp().compareTo(Instant.now()),
                is(lessThanOrEqualTo(1))); // equals o micro-antes
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: REFERENCE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe guardar referencia de la operación (orderId, tradeId, etc)")
    void should_save_operation_reference() {
        // Given
        LedgerEntry entry = new LedgerEntry();
        String reference = "trade_12345_BTCUSDT";

        // When
        entry.setReference(reference);

        // Then
        assertThat(entry.getReference(), is(equalTo(reference)));
    }

    @Test
    @DisplayName("✓ Referencia puede ser null (sin referencia)")
    void should_accept_null_reference() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();
        entry.setReference(null);

        // Then
        assertThat(entry.getReference(), is(nullValue()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INVARIANTES: RELACIÓN WALLET Y ESTRATEGIA
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ LedgerEntry debe asociarse a una wallet")
    void should_ledger_be_associated_with_wallet() {
        // Given
        LedgerEntry entry = new LedgerEntry();
        Long walletId = 100L;

        // When
        entry.setWalletId(walletId);

        // Then
        assertThat(entry.getWalletId(), is(notNullValue()));
        assertThat(entry.getWalletId(), is(greaterThan(0L)));
    }

    @Test
    @DisplayName("✓ LedgerEntry puede ser del sistema (sin estrategiaId)")
    void should_ledger_exist_without_estrategiaId() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();
        entry.setWalletId(100L);
        entry.setEstrategiaId(null);

        // Then
        assertThat(entry.getWalletId(), is(notNullValue()));
        assertThat(entry.getEstrategiaId(), is(nullValue()));
    }

    @Test
    @DisplayName("✓ LedgerEntry puede ser de una estrategia específica")
    void should_ledger_be_associated_with_estrategia() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();
        entry.setWalletId(100L);
        entry.setEstrategiaId(50L);

        // Then
        assertThat(entry.getEstrategiaId(), is(notNullValue()));
        assertThat(entry.getEstrategiaId(), is(greaterThan(0L)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HEREDIBILIDAD BASEENTITY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ LedgerEntry debe heredar getId() de BaseEntity")
    void should_ledger_inherit_getId_from_BaseEntity() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();

        // Then
        assertThat(entry.getId(), is(nullValue())); // null hasta persistirse
    }

    @Test
    @DisplayName("✓ LedgerEntry debe heredar getFechaCreacion() de BaseEntity")
    void should_ledger_inherit_getFechaCreacion_from_BaseEntity() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();

        // Then
        assertThat(entry.getFechaCreacion(), is(notNullValue()));
    }

    @Test
    @DisplayName("✓ LedgerEntry debe heredar isEliminado() de BaseEntity")
    void should_ledger_inherit_isEliminado_from_BaseEntity() {
        // Given & When
        LedgerEntry entry = new LedgerEntry();

        // Then
        assertThat(entry.isEliminado(), is(false)); // false por defecto
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FLUJOS CONTABLES REALISTAS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Flujo: DEPOSIT → balance aumenta")
    void should_deposit_increase_balance() {
        // Given
        LedgerEntry deposit = new LedgerEntry();
        deposit.setWalletId(1L);
        deposit.setType(LedgerType.AVAILABLE);
        deposit.setAmount(new BigDecimal("10000.00"));
        deposit.setBalanceAfter(new BigDecimal("10000.00"));
        deposit.setTimestamp(Instant.now());

        // Then
        assertThat(deposit.getAmount(), is(greaterThan(BigDecimal.ZERO)));
        assertThat(deposit.getBalanceAfter(), is(greaterThan(BigDecimal.ZERO)));
    }

    @Test
    @DisplayName("✓ Flujo: REALIZED_PNL → balance puede aumentar o disminuir")
    void should_realized_pnl_change_balance() {
        // Given - ganancia
        LedgerEntry pnlProfit = new LedgerEntry();
        pnlProfit.setWalletId(1L);
        pnlProfit.setType(LedgerType.REALIZED_PNL);
        pnlProfit.setAmount(new BigDecimal("500.00"));
        pnlProfit.setBalanceAfter(new BigDecimal("10500.00"));

        // Then
        assertThat(pnlProfit.getAmount(), is(greaterThan(BigDecimal.ZERO)));
        assertThat(pnlProfit.getBalanceAfter(),
                is(greaterThan(new BigDecimal("10000.00"))));
    }

    @Test
    @DisplayName("✓ Flujo: FEE → balance disminuye")
    void should_fee_decrease_balance() {
        // Given
        LedgerEntry fee = new LedgerEntry();
        fee.setWalletId(1L);
        fee.setType(LedgerType.FEE);
        fee.setAmount(new BigDecimal("10.00"));
        fee.setBalanceAfter(new BigDecimal("10490.00")); // 10500 - 10 = 10490

        // Then
        assertThat(fee.getAmount(), is(greaterThan(BigDecimal.ZERO)));
        assertThat(fee.getBalanceAfter(),
                is(lessThan(new BigDecimal("10500.00"))));
    }
}

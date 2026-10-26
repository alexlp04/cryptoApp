package com.bottrading.interfaces.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.bottrading.interfaces.api.ApiViews.Pnl;
import com.bottrading.interfaces.api.ApiViews.Status;

@DisplayName("API de solo lectura: autenticación y rutas")
class ApiControllerTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123";
    private static final String AUTH = "Bearer " + TOKEN;

    private ApiQueryService queries;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queries = Mockito.mock(ApiQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ApiController(queries))
                .addFilters(new ApiTokenFilter(TOKEN)).build();
    }

    @Test
    @DisplayName("Sin token (o con uno incorrecto) todo da 401, salvo /health")
    void rejects_requests_without_a_valid_token() throws Exception {
        mvc.perform(get("/api/v1/strategies")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get("/api/v1/strategies").header("Authorization", "Bearer " + TOKEN + "x"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/strategies").header("Authorization", TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/wallets")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Con el token correcto se lee")
    void accepts_the_valid_token() throws Exception {
        when(queries.strategies()).thenReturn(List.of());

        mvc.perform(get("/api/v1/strategies").header("Authorization", AUTH)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("No hay ninguna operación de escritura: un POST no existe")
    void is_read_only() throws Exception {
        mvc.perform(post("/api/v1/strategies").header("Authorization", AUTH)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("/health no pide token: 200 si todo vive, 503 si falta un proceso o falla la base de datos")
    void health_is_public_and_reports_down() throws Exception {
        when(queries.status()).thenReturn(new Status("UP", List.of(1L), List.of()));
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));

        when(queries.status()).thenReturn(new Status("DOWN", List.of(1L), List.of(1L)));
        mvc.perform(get("/api/v1/health")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));

        when(queries.status()).thenThrow(new IllegalStateException("base de datos caída"));
        mvc.perform(get("/api/v1/health")).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("/health no filtra detalles: el detalle (/status) sí exige token")
    void status_detail_needs_the_token() throws Exception {
        when(queries.status()).thenReturn(new Status("DOWN", List.of(7L), List.of(7L)));

        mvc.perform(get("/api/v1/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/health")).andExpect(jsonPath("$.activas").doesNotExist());
        mvc.perform(get("/api/v1/status").header("Authorization", AUTH)).andExpect(jsonPath("$.sinProceso[0]").value(7));
    }

    @Test
    @DisplayName("Una estrategia inexistente da 404 y un estado desconocido, 400")
    void validates_the_strategy_and_the_state() throws Exception {
        when(queries.exists(99L)).thenReturn(false);
        when(queries.exists(1L)).thenReturn(true);

        mvc.perform(get("/api/v1/strategies/99/positions").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/strategies/1/positions?state=otro").header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Los resultados de una estrategia se devuelven con su serie")
    void returns_pnl() throws Exception {
        when(queries.exists(1L)).thenReturn(true);
        when(queries.pnl(1L)).thenReturn(new Pnl(2, 1, 1, java.math.BigDecimal.TEN, java.math.BigDecimal.ONE, List.of()));

        mvc.perform(get("/api/v1/strategies/1/pnl").header("Authorization", AUTH))
                .andExpect(jsonPath("$.operaciones").value(2)).andExpect(jsonPath("$.pnlTotal").value(10));
    }

    @Test
    @DisplayName("Con la API habilitada, un token ausente o corto impide arrancar")
    void refuses_to_start_without_a_strong_token() {
        assertThrows(IllegalStateException.class, () -> new ApiTokenFilter(""));
        assertThrows(IllegalStateException.class, () -> new ApiTokenFilter("corto"));
        assertThrows(IllegalStateException.class, () -> new ApiTokenFilter(null));
    }
}

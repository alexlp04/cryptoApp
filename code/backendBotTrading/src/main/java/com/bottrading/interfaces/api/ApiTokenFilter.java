package com.bottrading.interfaces.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Exige {@code Authorization: Bearer <token>} en toda la API de solo lectura, salvo {@code GET /api/v1/health}
 * (solo dice UP o DOWN, para el HEALTHCHECK de Docker). La API solo existe si se habilita el modo web; entonces el
 * token es obligatorio: sin él la aplicación no arranca en vez de abrir un puerto sin protección.
 *
 * <p>shortcut: un único token compartido, sin caducidad ni límite de intentos; con un token aleatorio de 32+ caracteres
 * y acceso solo por VPN basta. Pasar a un token por dispositivo cuando haya más de una persona.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiTokenFilter extends OncePerRequestFilter {

    static final int MIN_TOKEN_LENGTH = 32;
    static final String HEALTH_PATH = "/api/v1/health";
    private static final String BEARER = "Bearer ";

    private final byte[] token;

    public ApiTokenFilter(@Value("${cryptoapp.api.token:}") String token) {
        if (token == null || token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("La API está habilitada pero CRYPTOAPP_API_TOKEN falta o tiene menos de "
                    + MIN_TOKEN_LENGTH + " caracteres. Genera uno: openssl rand -hex 32");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("GET".equals(request.getMethod()) && HEALTH_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        boolean valido = header != null && header.startsWith(BEARER)
                && MessageDigest.isEqual(header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8), token);
        if (!valido) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}

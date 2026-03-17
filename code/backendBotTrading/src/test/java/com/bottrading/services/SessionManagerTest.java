package com.bottrading.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bottrading.beans.Usuario;

@ExtendWith(MockitoExtension.class)
class SessionManagerTest {

    @Mock
    private EstrategiaService estrategiaService;

    @Test
    void logoutWithoutCurrentUserShouldNotThrow() {
        SessionManager sessionManager = new SessionManager(estrategiaService);

        assertDoesNotThrow(sessionManager::logout);
        assertFalse(sessionManager.isLoggedIn());
        assertNull(sessionManager.getCurrentUser());
    }

    @Test
    void logoutWithCurrentUserShouldClearSession() {
        SessionManager sessionManager = new SessionManager(estrategiaService);
        sessionManager.login(new Usuario("alejandro", "hash"));

        sessionManager.logout();

        assertFalse(sessionManager.isLoggedIn());
        assertNull(sessionManager.getCurrentUser());
    }

    @Test
    void loginShouldMarkSessionAsLoggedIn() {
        SessionManager sessionManager = new SessionManager(estrategiaService);

        sessionManager.login(new Usuario("alejandro", "hash"));

        assertTrue(sessionManager.isLoggedIn());
    }
}

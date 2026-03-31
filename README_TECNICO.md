# 📚 Documentación Técnica - backendBotTrading

## 🏗️ Arquitectura

**Archivo**: [ARQUITECTURA.md](./ARQUITECTURA.md) ← **COMIENZA AQUÍ**

Documentación comprensiva de la Arquitectura Hexagonal implementada en el proyecto:
- 📐 Capas (Domain, Application, Infrastructure, Interfaces)
- 🔑 Patrón de Implementación con Usuario como template
- ✅ Validación & Tests (13/13 PASSED)
- 🚀 Cómo Replicar en otras entidades
- 💡 Beneficios demostrados

### Contenido del Archivo

1. **Problema & Solución** - Contexto de la refactorización
2. **Arquitectura de Capas** - Responsabilidades de cada capa
3. **Patrón de Implementación** - 8 pasos con código de ejemplo
4. **Validación & Tests** - Checklist y resultados
5. **Cómo Replicar** - Checklist para nuevas entidades
6. **Beneficios** - Evidencia de mejoras
7. **Próximos Pasos** - Roadmap con timeline

### Acceso Rápido

```bash
# Compilar
cd code/backendBotTrading
mvn clean compile

# Tests
mvn test -Dtest="UsuarioTest,UsuarioApplicationServiceTest"

# Todos los tests
mvn test
```

---

## 📊 Estado Actual

```
✅ Compilación:     BUILD SUCCESS
✅ Tests:           13/13 PASSED (8 domain + 5 application)
✅ Domain Puro:     Sin JPA, sin Spring
✅ Puertos:         application/port/{in, out}/
✅ Patrón:          Replicable → Vela, Posicion, Wallet, etc.
```

---

## 🗂️ Ubicación de Archivos

| Tipo | Ubicación | Descripción |
|------|-----------|-------------|
| **Arquitectura** | [ARQUITECTURA.md](./ARQUITECTURA.md) | Documentación única consolidada |
| **Código Java** | `code/backendBotTrading/src/main/java/com/bottrading/` | Fuente |
| **Tests** | `code/backendBotTrading/src/test/java/com/bottrading/` | Pruebas unitarias |
| **Config** | `code/backendBotTrading/src/main/resources/` | application.yml, logback |
| **Scripts** | `code/scripts/` | Python integration |
| **Estrategias** | `code/strategies/` | Trading strategies |

---

## 🎯 Entidades Refactorizadas

### ✅ Completado

- **Usuario** - Template pattern (Domain + Application + Infrastructure tests)

### ⏳ Próximas

1. **Vela** (máximo impacto)
2. **Posicion**
3. **InstanciaEstrategia**
4. **Wallet**
5. **IndicadorTecnico**

Ver [ARQUITECTURA.md - Cómo Replicar](./ARQUITECTURA.md#cómo-replicar) para detalles.

---

## 🔧 Desarrollo

### Requisitos

- Java 21+
- Maven 3.9+
- MySQL 8+
- Python 3.10+ (para scripts)

### Setup

```bash
cd code/backendBotTrading

# Compilar
mvn clean compile

# Tests
mvn test

# Instalar localmente
mvn clean install -DskipTests

# Con cobertura
mvn test jacoco:report
```

---

## 📖 Referencias

- 📄 [ARQUITECTURA.md](./ARQUITECTURA.md) - Guía completa
- 🔗 [backendBotTrading README](./code/backendBotTrading/README.md)
- 📚 [Domain-Driven Design](https://www.domainlanguage.com/ddd/)
- 🏗️ [Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/)

---

**Última actualización**: 31 de marzo de 2026  
**Versión**: 1.0 (Consolidado)

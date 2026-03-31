# Quality Scans (VS Code + Maven)

## Requisitos
- Java 21 configurado.
- Maven disponible.
- (Opcional) SonarQube Server/Cloud para Connected Mode.

## Tareas en VS Code
- `backend: test`
- `backend: verify-quality`
- `backend: full-quality`
- `backend: sonar-scanner`

## Comandos directos
```bash
cd code/backendBotTrading
mvn clean verify -DskipTests
mvn clean verify
mvn sonar:sonar
```

## Que se ejecuta en `verify`
- Checkstyle (estilo)
- PMD (best practices/error-prone/security/performance)
- SpotBugs (bug patterns)
- OWASP Dependency-Check (vulnerabilidades CVE)
- JaCoCo report (cobertura)

## Notas
- Dependency-Check puede tardar la primera vez por descarga de feeds CVE.
- Si SonarQube no esta conectado, `sonar:sonar` puede fallar por autenticacion.

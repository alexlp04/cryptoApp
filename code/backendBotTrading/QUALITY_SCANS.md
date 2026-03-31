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

## Pre-push quality gate (automatico)
```bash
cd /home/alejandro/Documentos/Informatica/cryptoapp
bash scripts/install-git-hooks.sh
```

Despues de instalarlo, cada `git push` ejecuta automaticamente:
```bash
cd code/backendBotTrading
mvn clean verify
```

Si falla algun test o regla de calidad, el push se bloquea.

## Que se ejecuta en `verify`
- Checkstyle (estilo)
- PMD (best practices/error-prone/security/performance)
- SpotBugs (bug patterns)
- OWASP Dependency-Check (vulnerabilidades CVE)
- JaCoCo report (cobertura)

## Notas
- Dependency-Check puede tardar la primera vez por descarga de feeds CVE.
- Si SonarQube no esta conectado, `sonar:sonar` puede fallar por autenticacion.

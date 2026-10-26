# Despliegue en una Raspberry Pi 5 (Docker)

Una imagen con Java y los motores Python, más MySQL 8, orquestadas con `docker-compose.yml`. Los modelos se
entrenan en tu PC y se copian a la Pi; en la Pi solo corre el paper trading.

## Qué necesitas
- Raspberry Pi 5 (8 GB recomendado) con Raspberry Pi OS de 64 bits y Docker con el plugin Compose.
- **Un SSD** para `deploy/` en vez de la tarjeta SD: MySQL escribe sin parar y la SD se desgasta.
- La clave de firma de modelos de tu PC (`~/.config/cryptoapp/model.key`). Sin ella la Pi rechaza tus modelos.

## Primera puesta en marcha
```bash
git clone https://github.com/alexlp04/cryptoApp.git && cd cryptoApp/deploy
cp env-example .env && nano .env                    # contraseñas largas y distintas
mkdir -p secrets data/models data/results data/logs
cp /ruta/a/model.key secrets/model.key && chmod 600 secrets/model.key
docker compose up -d --build
```
La primera construcción tarda (Maven y paquetes Python); las siguientes aprovechan la caché. El usuario del
contenedor es el uid 1000, el del primer usuario de Raspberry Pi OS: `data/` y `secrets/model.key` deben ser suyos.

Para construir en el PC y no en la Pi: `docker buildx build --platform linux/arm64 -f deploy/Dockerfile -t cryptoapp .`
(necesita QEMU: `docker run --privileged --rm tonistiigi/binfmt --install arm64`) y pasar la imagen con
`docker save cryptoapp | ssh pi docker load`.

## Uso diario
| Qué | Cómo |
|---|---|
| Usar la CLI (crear usuario, wallet, estrategias) | `docker attach cryptoapp-app-1`; salir sin parar la app: `Ctrl-P` `Ctrl-Q` |
| Ver logs | `docker compose logs -f app` y `data/logs/` |
| Llevar un modelo entrenado | copia el `.pkl` y su `.manifest.json` a `data/models/` (`rsync`/`scp`) |
| Desplegar una estrategia o cambios de código | commit, `git pull` en la Pi y `docker compose up -d --build` (las estrategias van dentro de la imagen) |
| Parar | `docker compose stop`: pausa las estrategias activas con los fondos en reserva; al volver a arrancar se reanudan solas |
| Parar una estrategia de forma definitiva | `stop` en la CLI: no se reanuda sola; vuelve con `start` |
| Copia de la base | `docker compose exec db sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" bottradingdb' > backup.sql` |

El contenedor se reinicia solo (`restart: unless-stopped`), también tras un corte de luz, y **las estrategias que estaban
en marcha se reanudan solas** al arrancar (`CRYPTOAPP_AUTORESUME`): tanto tras una parada ordenada como tras una caída
brusca (probado con `docker stop` y con `SIGKILL`). Las que pausaste a mano con `stop` se quedan pausadas. Una estrategia
que ya estaba parada antes de actualizar a esta versión no tiene la marca y no se reanuda sola.

## Seguridad
- No se publica ningún puerto: ni la CLI ni MySQL se ven desde la red. Para el acceso desde el móvil, cuando exista
  la API, usa una VPN (Tailscale o WireGuard) y no abras puertos en el router.
- La app usa su propio usuario de MySQL, con permisos solo sobre `bottradingdb`; root solo existe dentro del contenedor.
- La clave de firma de modelos entra como secreto de Docker (`/run/secrets/model_key`), no como variable de entorno visible.
- `deploy/.env`, `secrets/` y `data/` están en `.gitignore`. No los subas nunca.
- Modo real: sigue rechazado (no hay adaptador de exchange). Esto es paper trading.

## Limitaciones conocidas
- **TensorFlow / modelos Keras:** no hay rueda de `tensorflow==2.21.0` para arm64 (la última es la 2.20.0), así que la imagen
  no lo trae y no carga modelos Keras; sí los de scikit-learn, XGBoost y LightGBM. Para Keras en la Pi habría que fijar en
  el PC y en la imagen una versión que exista para arm64 y reentrenar: el manifiesto rechaza otra versión mayor.menor.
- **Versiones de librerías:** el PC usa `requirements.txt` y la imagen `deploy/requirements.lock` (mismas versiones, con
  hashes). Si cambias una versión, regenera el bloqueo con `pip install uv && deploy/lock-requirements.sh` (un test
  falla si se desincronizan) y reentrena los modelos afectados.
- **Sin healthcheck de la app:** Docker reinicia el contenedor si el proceso muere, pero no detecta un motor Python colgado.
  La API de estado lo resolverá.
- **Probado en x86:** la imagen se ha construido y arrancado en x86. En arm64 las dependencias tienen rueda (comprobado
  con `pip download`), pero la primera construcción en la Pi es la prueba real.

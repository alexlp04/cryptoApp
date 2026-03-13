# cryptoApp

CryptoApp es un bot de trading automático que implementa y gestiona estrategias de inversión mediante modelos de computación.
Este proyecto forma parte del Trabajo de Fin de Grado (TFG) en la carrera de Ingeniería Informática de la Universidad de Murcia.

## 🚀 Características principales

Por ahora unicamente puedes loggearte y guardar en la base de datos los datos sobre las velas de una moneda en un intervalo

## 🛠️ Tecnologías utilizadas

Lenguaje principal: Java y Python

Broker: Binance

Base de datos: MySQL

## 📂 Diagrama de Clases

![alt text](/docs/img/diagramaDeClases.png)

## ⚙️ Instalación y uso

### Clonar el repositorio

git clone https://github.com/alexlp04/cryptoApp.git
cd CryptoApp

### Configurar variables de entorno

Crea una carpeta .env donde añadir tus variables de entorno con tus credenciales de API y parámetros:

DB_URL: Con la URL de tu base de datos
DB_USER: Con el nombre de usuario que tendrá acceso a dicha base de datos
DB_PASS: La contraseña de ese usuario

### Ejecutar el bot

Desde la carpeta cryptoApp/ ejecuta los siguientes comandos en Windows

cd backedBotTrading/
mvn clean package
java -jar target/backendBotTrading-1.0-SNAPSHOT-shaded.jar

Y para Mac

cd backedBotTrading/
mvn clean package -DskipTests
java -jar target/backendBotTrading-1.0-SNAPSHOT-shaded.jar

### Documentación

La documentación completa del proyecto, incluyendo la memoria del TFG, se encuentra en la carpeta /docs (Sin hacer)

👨‍🎓 Autor

Proyecto desarrollado por Alejandro López López
Universidad de Murcia – Grado en Ingeniería Informática

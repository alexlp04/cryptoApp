-- Desde qué instante se ha descargado con éxito cada (símbolo, timeframe) (issue #116).
-- Sin esto, un par más joven que la ventana pedida (o un hueco real de Binance) se volvía a
-- descargar entero en cada ejecución porque la detección de huecos nunca convergía.

CREATE TABLE cobertura_velas (
    symbol VARCHAR(10) NOT NULL,
    time_interval VARCHAR(10) NOT NULL,
    cubierto_desde BIGINT NOT NULL,
    PRIMARY KEY (symbol, time_interval)
);

package com.bottrading.shared.utils;

/**
 * Constantes globales de la aplicación.
 * Define las claves del protocolo de comunicación (JSON/CSV) entre Java y Python.
 */
public final class AppConstants {

    // Evitar instanciación
    private AppConstants() {
        throw new UnsupportedOperationException("Esta es una clase de utilidad y no puede ser instanciada");
    }

    /** Ruta al intérprete del venv en Windows, relativa a la raíz del proyecto. */
    static final String PYTHON_EXECUTABLE_WINDOWS = ".venv/Scripts/python.exe";

    /** Ruta al intérprete del venv en Linux y macOS, relativa a la raíz del proyecto. */
    static final String PYTHON_EXECUTABLE_POSIX = ".venv/bin/python3";

    /** Variable de entorno para apuntar a un intérprete distinto al del venv por defecto. */
    static final String PYTHON_EXECUTABLE_ENV_VAR = "CRYPTOAPP_PYTHON";

    /**
     * Ruta al ejecutable Python dentro del venv del proyecto.
     * Se valida al arrancar la aplicación (PythonEnvironmentValidator).
     *
     * El layout del venv depende del sistema operativo: Windows coloca el intérprete en
     * ".venv/Scripts/python.exe" mientras que Linux y macOS lo hacen en ".venv/bin/python3".
     * La variable de entorno CRYPTOAPP_PYTHON tiene prioridad si está definida.
     */
    public static final String PYTHON_EXECUTABLE = resolveDefaultPythonExecutable(
            System.getenv(PYTHON_EXECUTABLE_ENV_VAR),
            System.getProperty("os.name"));

    /**
     * Determina el intérprete por defecto. Visible para tests con el fin de cubrir
     * ambos sistemas operativos sin depender del que ejecute la suite.
     */
    static String resolveDefaultPythonExecutable(String envOverride, String osName) {
        if (envOverride != null && !envOverride.isBlank()) {
            return envOverride;
        }
        boolean windows = osName != null
                && osName.toLowerCase(java.util.Locale.ROOT).contains("win");
        return windows ? PYTHON_EXECUTABLE_WINDOWS : PYTHON_EXECUTABLE_POSIX;
    }

    public static final String KEY_SYMBOL = "symbol";
    public static final String KEY_TIMEFRAME = "timeframe";
    public static final String KEY_SIDE = "side";
    public static final String KEY_PRICE = "price";
    public static final String KEY_TIMESTAMP = "timestamp";
    public static final String KEY_PNL = "pnl";
    public static final String KEY_CAPITAL = "capital";
    
    public static final String KEY_OP_GANADAS = "op_ganadas";
    public static final String KEY_OP_PERDIDAS = "op_perdidas";
    public static final String KEY_OP_TOTALES = "op_totales";
    public static final String KEY_MAX_DRAWDOWN = "max_drawdown";
    public static final String KEY_ABS_DRAWDOWN = "abs_drawdown";
    public static final String KEY_RET_ACUMULADO = "retorno_acumulado";
    public static final String KEY_RET_TOTAL = "retorno_total";
    public static final String KEY_WIN_RATE = "win_rate";
    public static final String KEY_PROFIT_FACTOR = "profit_factor";
    public static final String KEY_FECHA_INICIO = "fecha_inicio";
    public static final String KEY_FECHA_FIN = "fecha_fin";
    public static final String KEY_RESULTADO = "resultado";

    /**
     * Vocabulario del campo 'resultado' del CSV de stats.
     * La fuente de verdad es Python (backtest_engine.py), que es quien produce los
     * backtests; el paper trading en vivo escribe en el mismo fichero y debe usar
     * exactamente estos valores. Antes emitia PROFIT/LOSS y el campo quedaba con dos
     * vocabularios mezclados segun quien hubiera escrito la fila.
     */
    public static final String RESULTADO_GANANCIA = "GANANCIA";
    public static final String RESULTADO_PERDIDA = "PERDIDA";
    public static final String RESULTADO_NEUTRO = "NEUTRO";

    public static final String KEY_ERROR = "ERROR";
        public static final String KEY_ACTIVA = "ACTIVA";
    public static final String KEY_DETENIDA = "DETENIDA";
    public static final String KEY_TERMINADA = "TERMINADA";
    public static final String KEY_CREADA = "CREADA";


    public static final String CSV_HEADER_TRADES = String.join(",", 
            KEY_SYMBOL, KEY_TIMEFRAME, KEY_SIDE, KEY_PRICE, KEY_TIMESTAMP, KEY_PNL, KEY_CAPITAL);

    public static final String CSV_HEADER_STATS = String.join(",", 
            KEY_SYMBOL, KEY_TIMEFRAME, KEY_OP_GANADAS, KEY_OP_PERDIDAS, KEY_OP_TOTALES, 
            KEY_MAX_DRAWDOWN, KEY_ABS_DRAWDOWN, KEY_RET_ACUMULADO, KEY_RET_TOTAL, 
            KEY_WIN_RATE, KEY_PROFIT_FACTOR, KEY_FECHA_INICIO, KEY_FECHA_FIN, KEY_RESULTADO);

    public static final String DIR_BACKEND = "backendBotTrading";
    public static final String DIR_SCRIPTS = "scripts";
    public static final String DIR_STRATEGIES = "strategies";
    public static final String DIR_RESULTS = "results";

    public static final String FILE_ENGINE_RT = "engine_rt.py";
    public static final String FILE_ENGINE_BACKTEST = "engine_backtest.py";
    public static final String FILE_FETCHER = "engine_fetch.py";
    public static final String FILE_INDICATORS = "engine_indicators.py";

    public static final String FILE_ENGINE_TRAIN = "engine_train.py";
    public static final String FILE_ENGINE_OPTIMIZE = "engine_optimize.py";
public static final String DIR_MODELS = "models";
    public static final String FILE_ENGINE_AI_RT = "engine_ai_rt.py";
    
    public static final String EXTENSION_PYTHON = ".py";
    public static final String EXTENSION_ENV = ".env";
}
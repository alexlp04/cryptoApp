package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;

/**
 * Handles models command.
 */
public final class ModelsCommand implements CliCommand {

    @Override
    public String name() {
        return "models";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        context.println().accept("\n=================================================================================");
        context.println().accept("   CATALOGO DE MODELOS DE INTELIGENCIA ARTIFICIAL Y SUS HIPERPARAMETROS   ");
        context.println().accept("=================================================================================\n");

        context.println().accept("Uso en entrenamiento: train -m <modelo> -tf 15m -c BTCUSDT -params k1=v1,k2=v2\n");

        context.println().accept("   1. RANDOM FOREST (-m random_forest) [Recomendado para empezar]");
        context.println().accept("   El mas robusto. Crea multiples arboles de decision y votan el resultado.");
        context.println().accept("   > n_estimators (Int) : Numero de arboles (100-500). Def: 100");
        context.println().accept("   > max_depth    (Int) : Profundidad maxima del arbol (5-20). Def: 10");
        context.println().accept("   > min_samples_split (Int): Minimo de velas para crear rama (2-20).\n");

        context.println().accept("   2. XGBOOST (-m xgboost) [Alta Precision]");
        context.println().accept("   El rey del Machine Learning. Muy potente pero propenso a memorizar ruido.");
        context.println().accept("   > n_estimators  (Int)  : Iteraciones de aprendizaje (100-1000). Def: 150");
        context.println().accept("   > learning_rate (Float): Tasa de aprendizaje (0.01-0.2). Def: 0.05");
        context.println().accept("   > max_depth     (Int)  : Profundidad (3-10). Def: 6");
        context.println().accept("   > gamma         (Float): Filtro anti-ruido, reduccion minima (0.0-5.0).\n");

        context.println().accept("   3. LIGHTGBM (-m lightgbm) [Maxima Velocidad]");
        context.println().accept("   Ideal para entrenar anos de datos en temporalidades pequenas (1m, 5m).");
        context.println().accept("   > num_leaves    (Int)  : Hojas por arbol (20-100). Def: 31");
        context.println().accept("   > learning_rate (Float): Tasa de aprendizaje (0.01-0.2). Def: 0.05");
        context.println().accept("   > max_depth     (Int)  : Profundidad (3-12). Def: 6\n");

        context.println().accept("   4. GRADIENT BOOSTING (-m gradient_boosting) [Clasico]");
        context.println().accept("   > n_estimators  (Int)  : (100-500). Def: 100");
        context.println().accept("   > learning_rate (Float): (0.01-0.2). Def: 0.1\n");

        context.println().accept("   5. SUPPORT VECTOR MACHINES (-m svm) [Matematico]");
        context.println().accept("   Detecta regimenes de mercado creando fronteras matematicas.");
        context.println().accept("   > C             (Float): Margen de error (0.1-100). Def: 1.0");
        context.println().accept("   > kernel        (Str)  : Forma ('rbf', 'linear', 'poly'). Def: rbf\n");

        context.println().accept("   6. DEEP LEARNING / RED NEURONAL (-m neural_network) [Avanzado]");
        context.println().accept("   TensorFlow/Keras. Excelente si le pasas muchos indicadores.");
        context.println().accept("   > epochs        (Int)  : Vueltas completas al dataset (10-100). Def: 50");
        context.println().accept("   > batch_size    (Int)  : Velas procesadas de golpe (32, 64, 128). Def: 64");
        context.println().accept("   > learning_rate (Float): Velocidad de ajuste (0.001-0.0001).");
        context.println().accept("   > dropout_rate  (Float): Apaga neuronas para evitar sobreajuste (0.2-0.5).\n");

        context.println().accept("=================================================================================");
    }
}

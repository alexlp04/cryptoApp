package com.bottrading.config;

import com.bottrading.utils.PathConfig;

import ch.qos.logback.core.PropertyDefinerBase;

/**
 * Definer de Logback que resuelve la ruta de logs apuntando
 * siempre a la raíz del proyecto (PROJECT_ROOT/logs),
 * independientemente del directorio de trabajo de la JVM.
 */
public class LogPathDefiner extends PropertyDefinerBase {

    @Override
    public String getPropertyValue() {
        return PathConfig.PROJECT_ROOT + "/logs";
    }
}

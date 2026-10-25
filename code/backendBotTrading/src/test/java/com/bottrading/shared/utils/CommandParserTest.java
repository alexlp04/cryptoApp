package com.bottrading.shared.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandParserTest {

    private static CommandParser parse(String line) {
        return new CommandParser(line.split("\\s+"));
    }

    @Test
    @DisplayName("Un comando correcto se parsea sin errores")
    void valid_command_is_parsed() {
        CommandParser p = parse("trade -v -strategy Rsi -tf 1h -coins btcusdt ethusdt");

        assertFalse(p.hasErrorSintaxis());
        assertTrue(p.isVirtual());
        assertEquals("Rsi", p.getEstrategia());
        assertEquals("1h", p.getTimeframe());
        assertEquals(java.util.List.of("BTCUSDT", "ETHUSDT"), p.getCoins());
    }

    @Test
    @DisplayName("Una bandera desconocida da error en vez de ignorarse (#106)")
    void unknown_flag_is_an_error() {
        CommandParser p = parse("trade -strategie Rsi -tf 1h -coins BTCUSDT");

        assertTrue(p.hasErrorSintaxis());
        assertTrue(p.getMensajeError().contains("-strategie"));
    }

    @Test
    @DisplayName("Un par de -params mal formado da error en vez de descartarse (#106)")
    void malformed_param_pairs_are_errors() {
        for (String bad : new String[] {"max_depth:6", "lr=", "=5"}) {
            CommandParser p = parse("train -model xgboost -tf 1h -coins BTCUSDT -params " + bad);

            assertTrue(p.hasErrorSintaxis(), bad);
            assertTrue(p.getMensajeError().contains("clave=valor"), bad);
        }
    }

    @Test
    @DisplayName("Los hiperparámetros se tipan: notación científica, booleanos y enteros grandes (#106)")
    void hyperparameters_are_typed() {
        CommandParser p = parse("train -model xgboost -tf 1h -coins BTCUSDT "
                + "-params learning_rate=1e-3,depth=6,ratio=0.5,flag=True,big=99999999999,name=abc,expr=a=b");

        assertFalse(p.hasErrorSintaxis());
        assertEquals(0.001, (Double) p.getHyperparams().get("learning_rate"), 1e-12);
        assertEquals(6, p.getHyperparams().get("depth"));
        assertEquals(0.5, p.getHyperparams().get("ratio"));
        assertEquals(true, p.getHyperparams().get("flag"));
        assertEquals(99_999_999_999L, p.getHyperparams().get("big"));
        assertEquals("abc", p.getHyperparams().get("name"));
        assertEquals("a=b", p.getHyperparams().get("expr")); // el segundo = forma parte del valor
    }

    @Test
    @DisplayName("-d -5 dice que debe ser positivo (no 'falta valor') y -d 0 se rechaza (#106)")
    void non_positive_days_are_rejected_with_the_right_message() {
        CommandParser negative = parse("fetch BTCUSDT 1h -d -5");
        CommandParser zero = parse("fetch BTCUSDT 1h -d 0");

        assertTrue(negative.hasErrorSintaxis());
        assertTrue(negative.getMensajeError().contains("positivo"));
        assertTrue(zero.hasErrorSintaxis());
        assertTrue(zero.getMensajeError().contains("positivo"));
    }

    @Test
    @DisplayName("Un valor ausente cita la bandera que falló")
    void missing_value_names_the_flag() {
        CommandParser p = parse("fetch BTCUSDT 1h -d");

        assertTrue(p.hasErrorSintaxis());
        assertTrue(p.getMensajeError().contains("-d"));
    }

    @Test
    @DisplayName("-coins sin ninguna moneda es un error (#106)")
    void coins_without_values_is_an_error() {
        CommandParser p = parse("backtest -strategy Rsi -tf 1h -coins");

        assertTrue(p.hasErrorSintaxis());
        assertTrue(p.getMensajeError().contains("-coins"));
    }

    @Test
    @DisplayName("-min-composite acepta (0, 100] y el mensaje de error es coherente")
    void min_composite_range() {
        assertFalse(parse("optimize -mc 0.5").hasErrorSintaxis());
        assertTrue(parse("optimize -mc 0").hasErrorSintaxis());
        assertTrue(parse("optimize -mc 101").getMensajeError().contains("máximo 100"));
    }
}

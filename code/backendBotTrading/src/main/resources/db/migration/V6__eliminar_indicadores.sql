-- Se elimina el pipeline de indicadores técnicos (issue #88).
--
-- Nadie leía indicador_tecnico (5 filas por vela): el entrenamiento y el backtest calculan sus propios
-- indicadores con strategy.populate_indicators. Son datos derivados y se pueden recalcular, así que
-- el borrado no pierde información propia. Si la UI futura necesita indicadores, se calculan bajo demanda.

DROP TABLE IF EXISTS indicador_tecnico;

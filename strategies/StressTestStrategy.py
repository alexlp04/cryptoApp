from strategies.BaseStrategy import BaseStrategy

class StressTestStrategy(BaseStrategy):
    def __init__(self, capital=1000, risk_per_trade=0.02):
        super().__init__(capital, risk_per_trade)
        # Estado interno para alternar entre compra y venta
        self.en_posicion = False

    def populate_indicators(self, df):
        # No necesitamos indicadores para esta prueba, devolvemos el df tal cual
        return df

    def should_buy(self, row):
        # Si no estamos en posición, compramos inmediatamente
        if not self.en_posicion:
            self.en_posicion = True
            return True
        return False

    def should_sell(self, row):
        # Si estamos en posición, vendemos inmediatamente en la siguiente vela
        if self.en_posicion:
            self.en_posicion = False
            return True
        return False
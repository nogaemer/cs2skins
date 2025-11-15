package tradeup

import models.Skin

data class TradeUpOutput(
    val skins: MutableList<Skin>,
    val costs: Double
)

package de.nogaemer.cs2skinsv2.tradeup.model

import de.nogaemer.cs2skinsv2.catalog.model.Skin

data class TradeUpOutput(
    val skins: MutableList<Skin>,
    val costs: Double
)

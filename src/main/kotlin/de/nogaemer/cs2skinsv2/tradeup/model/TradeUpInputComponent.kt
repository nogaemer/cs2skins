package de.nogaemer.cs2skinsv2.tradeup.model

import de.nogaemer.cs2skinsv2.catalog.model.Skin

class TradeUpInputComponent(
    val skin: Skin,
    val amount: Int,
    val collectionId: String? = null
)

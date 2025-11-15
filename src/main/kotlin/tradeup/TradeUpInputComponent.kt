package tradeup

import models.Skin

class TradeUpInputComponent(
    val skin: Skin,
    val amount: Int,
    val collectionId: String? = null
)

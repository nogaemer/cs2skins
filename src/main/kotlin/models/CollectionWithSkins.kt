package models

import database.SkinDTO

data class CollectionWithSkins (
    val collectionId: String,
    val name: String,
    val image: String?,
    val skins: MutableMap<String, MutableList<SkinDTO>>// rarity: Rarity, skins: List<Skin>
)
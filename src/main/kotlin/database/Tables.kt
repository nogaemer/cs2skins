package database

import models.CSWear
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import java.math.BigDecimal

object Collections : Table("collections") {
    val collectionId = varchar("collection_id", 255)
    val name = varchar("name", 255)
    val image = text("image").nullable()

    override val primaryKey = PrimaryKey(collectionId)
}

object Weapons : Table("weapons") {
    val weaponId = varchar("weapon_id", 255)
    val name = varchar("name", 255)
    val image = text("image").nullable()

    override val primaryKey = PrimaryKey(weaponId)
}

object Rarities : Table("rarities") {
    val rarityId = varchar("rarity_id", 255)
    val name = varchar("name", 100)
    val colorHex = char("color_hex", 6).nullable()

    override val primaryKey = PrimaryKey(rarityId)
}


object WearConditions : Table("wear_conditions") {
    val wearId = varchar("wear_id", 255)
    val name = varchar("name", 100)

    override val primaryKey = PrimaryKey(wearId)
}

object Skins : Table("skins") {
    val skinId = varchar("skin_id", 255)
    val collectionId = varchar("collection_id", 255)
        .references(Collections.collectionId, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE)
        .nullable()
    val name = varchar("name", 255)
    val weaponId = varchar("weapon_id", 255)
        .references(Weapons.weaponId, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE)
        .nullable()
    val patternId = varchar("pattern_id", 100).nullable()
    val patternName = varchar("pattern_name", 255).nullable()
    val minFloat = double("min_float")
    val maxFloat = double("max_float")
    val rarityId = varchar("rarity_id", 255)
        .references(Rarities.rarityId, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.CASCADE)
        .nullable()
    val stattrak = bool("stattrak").default(false)
    val image = text("image").nullable()

    override val primaryKey = PrimaryKey(skinId)

    init {
        index("idx_skins_collection", false, collectionId)
        index("idx_skins_weapon", false, weaponId)
        index("idx_skins_rarity", false, rarityId)
    }
}

object SkinPrices : Table("skin_prices") {
    val id = integer("id").autoIncrement()
    val skinId = varchar("skin_id", 255)
        .references(Skins.skinId, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val wearId = varchar("wear_id", 255)
        .references(WearConditions.wearId, onDelete = ReferenceOption.RESTRICT, onUpdate = ReferenceOption.CASCADE)
    val price = decimal("price", 10, 2).default(java.math.BigDecimal.ZERO)
    val quantity = integer("quantity").default(0)

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_prices_skin", false, skinId)
        index("idx_prices_wear", false, wearId)
    }
}

data class Collection(
    val collectionId: String,
    val name: String,
    val image: String?
)

data class Weapon(
    val weaponId: String,
    val name: String,
    val image: String?
)

data class Rarity(
    val rarityId: String,
    val name: String,
    val colorHex: String?
)

data class WearCondition(
    val wearId: String,
    val name: String
)

data class SkinDTO(
    val skinId: String,
    val collectionId: String?,
    val name: String,
    val weapon: Weapon,
    val price: MutableMap<CSWear, SkinPrice> = mutableMapOf(),
    val patternId: String?,
    val patternName: String?,
    val minFloat: Double,
    val maxFloat: Double,
    val rarity: Rarity,
    val stattrak: Boolean,
    val image: String?
)

data class SkinPrice(
    val id: Int,
    val skinId: String,
    val wear: WearCondition,
    val price: BigDecimal,
    val quantity: Int
)


// Skin with full details
data class SkinWithDetails(
    val skinDTO: SkinDTO,
    val collection: Collection?,
    val weapon: Weapon?,
    val rarity: Rarity?
)

// Price with wear condition name
data class PriceWithWear(
    val skinPrice: SkinPrice,
    val wearConditionName: String
)




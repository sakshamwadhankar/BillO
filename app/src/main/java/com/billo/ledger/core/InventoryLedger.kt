package com.billo.ledger.core

import com.billo.ledger.data.db.BillODatabase
import com.billo.ledger.data.db.InventoryItemEntity
import com.billo.ledger.data.db.ItemKind
import com.billo.ledger.data.db.StockMoveEntity

/**
 * Phase 4 facade over the inventory tables: items (raw / finished) and
 * physical stock moves. Moves record only what a vendor would naturally
 * note down — purchases and daily consumption — so losses stay invisible
 * until a stock count reveals them.
 *
 * The database handle is a public [val] on purpose: the seeder and the
 * inference engine reach the raw DAOs through it.
 */
class InventoryLedger(val database: BillODatabase) {

    private val dao get() = database.inventoryDao()

    /**
     * Insert-if-absent item registration. If the item already exists and a
     * positive cost is supplied, the unit cost is updated in place.
     */
    suspend fun addItem(name: String, kind: ItemKind, unit: String, unitCostPaise: Long = 0) {
        dao.insertItem(
            InventoryItemEntity(
                name = name,
                kind = kind,
                unit = unit,
                unitCostPaise = unitCostPaise,
            ),
        )
        if (unitCostPaise > 0) dao.updateUnitCost(name, unitCostPaise)
    }

    /** Records one physical stock movement (positive = in, negative = out). */
    suspend fun addMove(itemName: String, deltaQty: Double, reason: String, wallTimeMillis: Long) {
        dao.insertMove(
            StockMoveEntity(
                itemName = itemName,
                deltaQty = deltaQty,
                reason = reason,
                wallTimeMillis = wallTimeMillis,
            ),
        )
    }

    /** Book quantity on hand: sum of every recorded delta for the item. */
    suspend fun stockOnHand(itemName: String): Double =
        dao.movesFor(itemName).sumOf { it.deltaQty }

    suspend fun allItems(): List<InventoryItemEntity> = dao.allItems()

    suspend fun movesFor(itemName: String): List<StockMoveEntity> = dao.movesFor(itemName)
}

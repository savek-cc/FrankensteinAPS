package app.aaps.database.transactions

import app.aaps.database.entities.ExtendedBolus
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.entities.interfaces.end

/**
 * @param amount insulin the pump reports as actually delivered, null if it does not know it. Only
 *   pumps that record the delivered amount in their history can provide this.
 */
class SyncPumpCancelExtendedBolusIfAnyTransaction(
    private val timestamp: Long, private val endPumpId: Long, private val pumpType: InterfaceIDs.PumpType, private val pumpSerial: String, private val amount: Double? = null
) : Transaction<SyncPumpCancelExtendedBolusIfAnyTransaction.TransactionResult>() {

    override suspend fun run(): TransactionResult {
        val result = TransactionResult()
        val existing = database.extendedBolusDao.findByPumpEndIds(endPumpId, pumpType, pumpSerial)
        if (existing != null) // assume EB has been cut already
            return result
        val running = database.extendedBolusDao.getExtendedBolusActiveAt(timestamp)
        if (running != null && running.interfaceIDs.endId == null) { // do not allow overwrite if cut by end event
            if (amount != null) {
                running.amount = amount
            } else {
                // The pump does not tell us what it delivered, so the elapsed fraction of the
                // programmed duration is the best estimate available.
                val pctRun = (timestamp - running.timestamp) / running.duration.toDouble()
                running.amount *= pctRun
            }
            running.end = timestamp
            running.interfaceIDs.endId = endPumpId
            database.extendedBolusDao.updateExistingEntry(running)
            result.updated.add(running)
        }
        return result
    }

    class TransactionResult {

        val updated = mutableListOf<ExtendedBolus>()
    }
}
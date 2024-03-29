package app.aaps.database.transactions

import app.aaps.database.entities.GlucoseValue

/**
 * Updates the GlucoseValue
 */
class UpdateGlucoseValueTransaction(val glucoseValue: GlucoseValue) : Transaction<Unit>() {

    override fun run() {
        database.glucoseValueDao.updateExistingEntry(glucoseValue)
    }
}
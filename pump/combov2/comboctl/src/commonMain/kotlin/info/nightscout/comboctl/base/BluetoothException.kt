package info.nightscout.comboctl.base

/**
 * Base class for Bluetooth specific exceptions.
 *
 * @param message The detail message.
 * @param cause Throwable that further describes the cause of the exception.
 */
open class BluetoothException(message: String?, cause: Throwable?) : ComboIOException(message, cause) {
    constructor(message: String) : this(message, null)
    constructor(cause: Throwable) : this(null, cause)
}

/**
 * Base class for exceptions that get thrown when permissions for scanning, connecting etc. are missing.
 *
 * Subclasses contain OS specific information, like the exact missing permissions.
 *
 * @param message The detail message.
 * @param cause Throwable that further describes the cause of the exception.
 */
open class BluetoothPermissionException(message: String?, cause: Throwable?) : BluetoothException(message, cause) {
    constructor(message: String) : this(message, null)
    constructor(cause: Throwable) : this(null, cause)
}

/**
 * Exception thrown when trying to use Bluetooth even though the adapter is not enabled.
 *
 * Note that unlike [BluetoothNotAvailableException], here, the adapter _does_ exist,
 * and is just currently turned off.
 */
open class BluetoothNotEnabledException : BluetoothException("Bluetooth is not enabled")

/**
 * Exception thrown when trying to use Bluetooth even though there no adapter available.
 *
 * "Not available" typically means that the platform has no Bluetooth hardware, or that
 * said hardware is inaccessible.
 */
open class BluetoothNotAvailableException : BluetoothException("Bluetooth is not available - there is no usable adapter")

/**
 * Exception thrown when the device is reachable but does not offer the RFCOMM serial port service.
 *
 * This is distinct from the device being out of range. The Bluetooth link to the pump is
 * established, and the pump answers service discovery requests - it just does not advertise
 * a Serial Port Profile record (anymore), so there is no RFCOMM channel to connect to.
 * Observed on the Combo after it stopped registering that record; it keeps answering on the
 * Bluetooth level, which makes this look exactly like an unreachable pump unless the two
 * cases are told apart.
 *
 * The pump re-registers the record once it is woken up, so the user has to press one of its
 * buttons. Restarting the app or the Android Bluetooth stack does not help.
 */
open class BluetoothServiceNotOfferedException(message: String?, cause: Throwable?) : BluetoothException(message, cause) {
    constructor(message: String) : this(message, null)
}

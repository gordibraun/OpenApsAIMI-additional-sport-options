package app.aaps.combobench

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import info.nightscout.comboctl.base.*
import kotlinx.datetime.UtcOffset
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.Cipher as CryptoCipher

/** Pairing state belongs only to the bench. No AAPS keys are read or imported. */
internal class BenchPairingStore(context: Context, private val expectedAddress: BluetoothAddress,
                                 private val expectedPump: String) : PumpStateStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "combo-pairing.enc"))
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("combo-bench-pairing", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder("combo-bench-pairing", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun verify(address: BluetoothAddress) = check(address == expectedAddress) { "Unexpected pump address" }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    private fun read(): JSONObject {
        val encrypted = file.openRead().use { it.readBytes() }
        check(encrypted.size > 28)
        val cipher = CryptoCipher.getInstance("AES/GCM/NoPadding")
        cipher.init(CryptoCipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        return JSONObject(cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)).toString(Charsets.UTF_8)).also {
            check(it.getString("address") == expectedAddress.toString() && it.getString("pump") == expectedPump)
        }
    }

    private fun write(data: JSONObject) {
        val cipher = CryptoCipher.getInstance("AES/GCM/NoPadding")
        cipher.init(CryptoCipher.ENCRYPT_MODE, key)
        val encrypted = cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            stream.fd.sync()
            file.finishWrite(stream)
            check(read().toString() == data.toString()) { "Pairing state verification failed" }
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    override fun createPumpState(pumpAddress: BluetoothAddress, invariantPumpData: InvariantPumpData,
                                 utcOffset: UtcOffset, tbrState: CurrentTbrState) {
        verify(pumpAddress)
        check(!hasPumpState(pumpAddress)) { "Existing pairing will not be overwritten" }
        check(invariantPumpData.pumpID == expectedPump) { "Pump identity mismatch after handshake" }
        check(tbrState == CurrentTbrState.NoTbrOngoing)
        write(JSONObject().put("address", pumpAddress.toString()).put("pump", expectedPump)
            .put("cp", encode(invariantPumpData.clientPumpCipher.key))
            .put("pc", encode(invariantPumpData.pumpClientCipher.key))
            .put("keyAddress", invariantPumpData.keyResponseAddress.toInt())
            .put("nonce", Nonce.nullNonce().toString()).put("utc", utcOffset.totalSeconds))
    }

    override fun deletePumpState(pumpAddress: BluetoothAddress): Boolean {
        verify(pumpAddress)
        val existed = hasPumpState(pumpAddress)
        file.delete()
        check(!hasPumpState(pumpAddress))
        return existed
    }

    fun archiveForRePairing(): String {
        check(hasPumpState(expectedAddress)) { "No stored bench pairing to archive" }
        read() // Verify the encrypted record belongs to the expected pump before changing it.
        val bytes = file.openRead().use { it.readBytes() }
        val archive = AtomicFile(File(file.baseFile.parentFile, "combo-pairing-archived-${UUID.randomUUID()}.enc"))
        val stream = archive.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            archive.finishWrite(stream)
            check(archive.openRead().use { it.readBytes() }.contentEquals(bytes)) { "Archive verification failed" }
        } catch (e: Exception) {
            archive.failWrite(stream)
            throw e
        }
        file.delete()
        check(!hasPumpState(expectedAddress)) { "Original pairing could not be retired" }
        return archive.baseFile.name
    }
    override fun hasPumpState(pumpAddress: BluetoothAddress): Boolean {
        verify(pumpAddress)
        return file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    }
    override fun getAvailablePumpStateAddresses() = if (hasPumpState(expectedAddress)) setOf(expectedAddress) else emptySet()
    override fun getInvariantPumpData(pumpAddress: BluetoothAddress): InvariantPumpData {
        verify(pumpAddress)
        return read().let { InvariantPumpData(Cipher(decode(it.getString("cp"))), Cipher(decode(it.getString("pc"))),
            it.getInt("keyAddress").toByte(), it.getString("pump")) }
    }
    override fun getCurrentTxNonce(pumpAddress: BluetoothAddress): Nonce {
        verify(pumpAddress)
        return read().getString("nonce").toNonce()
    }
    override fun setCurrentTxNonce(pumpAddress: BluetoothAddress, currentTxNonce: Nonce) {
        verify(pumpAddress)
        write(read().put("nonce", currentTxNonce.toString()))
    }
    override fun getCurrentUtcOffset(pumpAddress: BluetoothAddress): UtcOffset {
        verify(pumpAddress)
        return UtcOffset(seconds = read().getInt("utc"))
    }
    override fun setCurrentUtcOffset(pumpAddress: BluetoothAddress, utcOffset: UtcOffset) {
        verify(pumpAddress)
        write(read().put("utc", utcOffset.totalSeconds))
    }
    override fun getCurrentTbrState(pumpAddress: BluetoothAddress): CurrentTbrState {
        verify(pumpAddress)
        read()
        return CurrentTbrState.NoTbrOngoing
    }
    override fun setCurrentTbrState(pumpAddress: BluetoothAddress, currentTbrState: CurrentTbrState) {
        verify(pumpAddress)
        check(currentTbrState == CurrentTbrState.NoTbrOngoing) { "TBR operations are unavailable in the pairing bench" }
    }
}

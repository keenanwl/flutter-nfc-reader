package it.matteocrippa.flutternfcreader

import android.Manifest
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.MifareClassic
import android.os.Build
import android.os.Handler;
import android.os.Looper;
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.StreamHandler
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.nio.charset.Charset


const val PERMISSION_NFC = 1007

class FlutterNfcReaderPlugin(val registrar: Registrar) : MethodCallHandler, EventChannel.StreamHandler, NfcAdapter.ReaderCallback {

    private val activity = registrar.activity()

    private var isReading = false
    private var nfcAdapter: NfcAdapter? = null
    private var nfcManager: NfcManager? = null

    private var eventSink: EventChannel.EventSink? = null
    private var handler: Handler? = null

    private var keyA: ByteArray = byteArrayOf()
    private var keyB: ByteArray = byteArrayOf()
    private var sectorRead: Int = 0

    private var kId = "nfcId"
    private var kContent = "nfcContent"
    private var kError = "nfcError"
    private var kStatus = "nfcStatus"

    private var READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A

    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar): Unit {
            val messenger = registrar.messenger()
            val channel = MethodChannel(messenger, "flutter_nfc_reader")
            val eventChannel = EventChannel(messenger, "it.matteocrippa.flutternfcreader.flutter_nfc_reader")
            val plugin = FlutterNfcReaderPlugin(registrar)
            channel.setMethodCallHandler(plugin)
            eventChannel.setStreamHandler(plugin)
        }
    }

    init {
        nfcManager = activity.getSystemService(Context.NFC_SERVICE) as? NfcManager
        nfcAdapter = nfcManager?.defaultAdapter
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result): Unit {

        when (call.method) {
            "NfcRead" -> {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    activity.requestPermissions(
                        arrayOf(Manifest.permission.NFC),
                        PERMISSION_NFC
                    )
                }

                val HEX_CHARS = "0123456789abcdef"
                val keyAHex: String? = call.argument("keyA")
                val keyBHex: String? = call.argument("keyB")
                val sectorReadIn: Int? = call.argument("sectorRead")

                if (keyAHex != null) {
                    val res = ByteArray(keyAHex.length / 2)

                    for (i in 0 until keyAHex.length step 2) {
                        val firstIndex = HEX_CHARS.indexOf(keyAHex[i])
                        val secondIndex = HEX_CHARS.indexOf(keyAHex[i + 1])

                        val octet = firstIndex.shl(4).or(secondIndex)
                        res[i.shr(1)] = octet.toByte()
                    }

                    this.keyA = res
                }

                if (keyBHex != null) {
                    val res = ByteArray(keyBHex.length / 2)

                    for (i in 0 until keyBHex.length step 2) {
                        val firstIndex = HEX_CHARS.indexOf(keyBHex[i])
                        val secondIndex = HEX_CHARS.indexOf(keyBHex[i + 1])

                        val octet = firstIndex.shl(4).or(secondIndex)
                        res[i.shr(1)] = octet.toByte()
                    }

                    this.keyB = res
                }

                if (sectorReadIn != null) {
                    this.sectorRead = sectorReadIn
                }

                if (!startNFC()) {
                    result.error("404", "ISSUE STARTING", null)
                    return
                }

                if (!isReading) {
                    result.error("404", "NFC Hardware not found", null)
                    return
                }

                result.success(null)
            }
            "NfcStop" -> {
                stopNFC()
                val data = mapOf(kId to "", kContent to "", kError to "", kStatus to "stopped")
                result.success(data)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    // EventChannel.StreamHandler methods
    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink?) {
        this.eventSink = eventSink
        this.handler = Handler(Looper.getMainLooper())
    }

    override fun onCancel(arguments: Any?) {
      eventSink = null
      stopNFC()
    }

    private fun startNFC(): Boolean {

        if (nfcAdapter == null) {
            val data = mapOf(kId to "", kContent to "", kError to "nfcAdapter null", kStatus to "")
            eventSink?.success(data)
        } else {
            val data = mapOf(kId to "", kContent to "", kError to "nfcAdapter NOT null", kStatus to "")
            eventSink?.success(data)
        }


        isReading = if (nfcAdapter?.isEnabled == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                nfcAdapter?.enableReaderMode(registrar.activity(), this, READER_FLAGS, null )

                val data = mapOf(kId to "", kContent to "", kError to "", kStatus to "readFail")
                eventSink?.success(data)
            }

            true
        } else {
            false
        }
        return isReading
    }

    private fun stopNFC() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            nfcAdapter?.disableReaderMode(registrar.activity())
        }
        isReading = false
        eventSink = null
    }

    // handle discovered NDEF Tags
    override fun onTagDiscovered(tag: Tag?) {

        val mfc = MifareClassic.get(tag)
        var sector = -1
        var msg = ""

        if (mfc != null) {

            mfc.connect()

            for (i in 0..mfc.blockCount) {

                var authenticated = false

                if(mfc.blockToSector(i) != sector) {

                    sector = mfc.blockToSector(i)

                    if (mfc.authenticateSectorWithKeyA(sector, MifareClassic.KEY_DEFAULT)) {

                        authenticated = true

                    } else if (this.keyA.isNotEmpty() && mfc.authenticateSectorWithKeyA(sector, this.keyA)) {

                        authenticated = true

                    } else if (this.keyB.isNotEmpty() && mfc.authenticateSectorWithKeyB(sector, this.keyB)) {

                        authenticated = true

                    }

                    if (authenticated && sector == this.sectorRead) {

                        val msgI = mfc.readBlock(i)

                        if (msgI != null && msgI.isNotEmpty()) {

                            msg += ""

                            var st = ""
                            for (b in msgI) {

                                st = st + String.format("%02X", b)

                            }

                            msg += st

                            val id = bytesToHexString(tag?.id) ?: ""

                            val data = mapOf(kId to id + "", kContent to msg, kError to "", kStatus to "read")

                            handler?.post { eventSink?.success(data) }

                            break

                        }




                    } else {

                        //msg += "\nSECISSUE:" + sector.toString()

                    }

                }

            }

            mfc.close()

        } else {

            val data = mapOf(kId to "", kContent to "", kError to "", kStatus to "read")
            eventSink?.success(data)

        }

    }

    private fun bytesToHexString(src: ByteArray?): String? {
        val stringBuilder = StringBuilder("0x")
        if (src == null || src.isEmpty()) {
            return null
        }

        val buffer = CharArray(2)
        for (i in src.indices) {
            buffer[0] = Character.forDigit(src[i].toInt().ushr(4).and(0x0F), 16)
            buffer[1] = Character.forDigit(src[i].toInt().and(0x0F), 16)
            stringBuilder.append(buffer)
        }

        return stringBuilder.toString()
    }
}
package it.matteocrippa.flutternfcreader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.nfc.*
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Handler
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.nio.charset.Charset
import android.os.Looper
import java.io.IOException

const val PERMISSION_NFC = 1007

class FlutterNfcReaderPlugin(registrar: Registrar) : MethodCallHandler, EventChannel.StreamHandler, NfcAdapter.ReaderCallback {

    private val activity = registrar.activity()

    private var nfcAdapter: NfcAdapter? = null
    private var nfcManager: NfcManager? = null


    private var kId = "nfcId"
    private var kContent = "nfcContent"
    private var kError = "nfcError"
    private var kStatus = "nfcStatus"
    private var kWrite = ""
    private var kPath = ""
    private var readResult: Result? = null
    private var writeResult: Result? = null
    private var tag: Tag? = null
    private var eventChannel: EventChannel.EventSink? = null

    private var keyA: ByteArray = byteArrayOf()
    private var keyB: ByteArray = byteArrayOf()
    private var sectorRead: Int? = 0

    private val HEX_CHARS = "0123456789abcdef"

    private var nfcFlags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_BARCODE or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V

    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val messenger = registrar.messenger()
            val channel = MethodChannel(messenger, "flutter_nfc_reader")
            val eventChannel = EventChannel(messenger, "it.matteocrippa.flutternfcreader.flutter_nfc_reader")
            val plugin = FlutterNfcReaderPlugin(registrar)
            channel.setMethodCallHandler(plugin)
            eventChannel.setStreamHandler(plugin)
        }
    }

    init {
        if(activity != null) {
            nfcManager = activity.getSystemService(Context.NFC_SERVICE) as? NfcManager
            nfcAdapter = nfcManager?.defaultAdapter

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activity.requestPermissions(
                        arrayOf(Manifest.permission.NFC),
                        PERMISSION_NFC
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                nfcAdapter?.enableReaderMode(activity, this, nfcFlags, null)
            }
        }
    }

    private fun writeMessageToTag(nfcMessage: NdefMessage, tag: Tag?): Boolean {

        try {
            val nDefTag = Ndef.get(tag)

            nDefTag?.let {
                it.connect()
                if (it.maxSize < nfcMessage.toByteArray().size) {
                    //Message too large to write to NFC tag
                    return false
                }
                return if (it.isWritable) {
                    it.writeNdefMessage(nfcMessage)
                    it.close()
                    //Message is written to tag
                    true
                } else {
                    //NFC tag is read-only
                    false
                }
            }

            val nDefFormatableTag = NdefFormatable.get(tag)

            nDefFormatableTag?.let {
                return try {
                    it.connect()
                    it.format(nfcMessage)
                    it.close()
                    //The data is written to the tag
                    true
                } catch (e: IOException) {
                    //Failed to format tag
                    false
                }
            }
            //NDEF is not supported
            return false

        } catch (e: Exception) {
            //Write operation has failed
        }
        return false
    }

    fun createNFCMessage(payload: String?, intent: Intent?): Boolean {

        val pathPrefix = "it.matteocrippa.flutternfcreader"
        val nfcRecord = NdefRecord(NdefRecord.TNF_EXTERNAL_TYPE, pathPrefix.toByteArray(), ByteArray(0), (payload as String).toByteArray())
        val nfcMessage = NdefMessage(arrayOf(nfcRecord))
        intent?.let {
            val tag = it.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            return writeMessageToTag(nfcMessage, tag)
        }
        return false
    }

    override fun onMethodCall(call: MethodCall, result: Result) {

        if (nfcAdapter?.isEnabled != true && call.method != "NfcAvailable") {
            result.error("404", "NFC Hardware not found", null)
            return
        }

        when (call.method) {
            "NfcStop" -> {
                readResult = null
                writeResult = null
            }

            "NfcRead" -> {
                readResult = result
                this.keyA = _MiFareKey(call.argument("keyA"))
                this.keyB = _MiFareKey(call.argument("keyB"))
                this.sectorRead = call.argument("sectorRead")
            }

            "NfcWrite" -> {
                writeResult = result
                kWrite = call.argument("label")!!
                kPath = call.argument("path")!!
                if (this.tag != null) {
                    writeTag()
                }
            }
            "NfcAvailable" -> {
                when {
                    nfcAdapter == null -> result.success("not_supported")
                    nfcAdapter!!.isEnabled -> result.success("available")
                    else -> result.success("disabled")
                }
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    // EventChannel.StreamHandler methods
    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventChannel = events
    }

    override fun onCancel(arguments: Any?) {
        eventChannel =  null
    }

    private fun _MiFareKey(key: String?): ByteArray {

        if (key != null) {

            val result = ByteArray(key.length / 2)

            for (i in key.indices step 2) {
                val firstIndex = this.HEX_CHARS.indexOf(key[i])
                val secondIndex = this.HEX_CHARS.indexOf(key[i + 1])

                val octet = firstIndex.shl(4).or(secondIndex)
                result[i.shr(1)] = octet.toByte()
            }

            return result

        }

        return ByteArray(0)

    }


    private fun stopNFC() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            nfcAdapter?.disableReaderMode(activity)
        }
    }


    private fun writeTag() {
        if (writeResult != null) {
            val nfcRecord = NdefRecord(NdefRecord.TNF_EXTERNAL_TYPE, kPath.toByteArray(), ByteArray(0), kWrite.toByteArray())
            val nfcMessage = NdefMessage(arrayOf(nfcRecord))
            writeMessageToTag(nfcMessage, tag)
            val data = mapOf(kId to "", kContent to kWrite, kError to "", kStatus to "write")
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                writeResult?.success(data)
                writeResult = null
            }
        }
    }


    private fun readTag() {

        val (success, message) = this.readMiFareTag(this.keyA, this.keyB, this.sectorRead, this.tag)

        if (!success) {

            // convert tag to NDEF tag
            val ndef = Ndef.get(tag)
            ndef?.connect()
            val ndefMessage = ndef?.ndefMessage ?: ndef?.cachedNdefMessage
            val message = ndefMessage?.toByteArray()
                    ?.toString(Charset.forName("UTF-8")) ?: ""

            ndef?.close()

        }

        val id = bytesToHexString(tag?.id) ?: ""
        val data = mapOf(kId to id, kContent to message, kError to "", kStatus to "reading")
        val mainHandler = Handler(Looper.getMainLooper())

        if (readResult != null) {
            mainHandler.post {
                readResult?.success(data)
                readResult = null
            }
        } else {
            mainHandler.post {
                eventChannel?.success(data)
            }
        }

    }


    private fun readMiFareTag(keyA: ByteArray, keyB: ByteArray, sectorRead: Int?, tag: Tag?): Pair<Boolean, String> {

        val mfc = MifareClassic.get(tag)
        var sector = -1
        var msg = ""
        var readSuccess = false

        if (mfc != null) {
            mfc.connect()

            for (i in 0..mfc.blockCount) {
                var authenticated = false

                if(mfc.blockToSector(i) != sector) {
                    sector = mfc.blockToSector(i)
                    if (mfc.authenticateSectorWithKeyA(sector, MifareClassic.KEY_DEFAULT)) {
                        authenticated = true
                    } else if (keyA.isNotEmpty() && mfc.authenticateSectorWithKeyA(sector, keyA)) {
                        authenticated = true
                    } else if (keyB.isNotEmpty() && mfc.authenticateSectorWithKeyB(sector, keyB)) {
                        authenticated = true
                    }

                    if (authenticated && sector == sectorRead) {
                        val msgI = mfc.readBlock(i)
                        if (msgI != null && msgI.isNotEmpty()) {
                            msg += ""
                            var st = ""
                            for (b in msgI) {
                                st += String.format("%02X", b)
                            }
                            msg += st
                            //val id = bytesToHexString(tag?.id) ?: ""

                            break
                        }
                        readSuccess = true

                    } else if (sector == sectorRead) {
                        // Could not read expected sector
                        mfc.close()

                        return Pair(readSuccess, msg)
                    }
                }
            }
            mfc.close()

        }

        return Pair(readSuccess, msg)

    }

    // handle discovered NDEF Tags
    override fun onTagDiscovered(tag: Tag?) {
        this.tag = tag
        writeTag()
        readTag()
        Handler(Looper.getMainLooper()).postDelayed({
            this.tag = null
        }, 2000)
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

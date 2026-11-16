package com.peaje.app.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

/**
 * Emula una tarjeta sin contacto (HCE). Android lo invoca cuando un lector envía
 * un SELECT con el AID declarado en res/xml/apduservice.xml.
 */
class PeajeHceService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return ProtocoloApdu.SW_LONGITUD_INCORRECTA
        val respuesta = ProtocoloApdu.procesar(commandApdu, SesionPagoNfc.tokenVigente())
        if (respuesta.tokenEntregado) {
            SesionPagoNfc.registrarEntrega()
            Log.i(TAG, "Token entregado al lector")
        } else {
            Log.i(TAG, "APDU respondida sin token (SW=${respuesta.bytes.takeLast(2).joinToString("") { "%02X".format(it) }})")
        }
        return respuesta.bytes
    }

    override fun onDeactivated(reason: Int) = Unit

    private companion object {
        const val TAG = "PeajeHce"
    }
}

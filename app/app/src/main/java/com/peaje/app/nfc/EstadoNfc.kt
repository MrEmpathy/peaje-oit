package com.peaje.app.nfc

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter

enum class EstadoNfc {
    DISPONIBLE,
    APAGADO,
    NO_SOPORTADO;

    companion object {
        fun de(context: Context): EstadoNfc {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
                return NO_SOPORTADO
            }
            val adaptador = NfcAdapter.getDefaultAdapter(context) ?: return NO_SOPORTADO
            return if (adaptador.isEnabled) DISPONIBLE else APAGADO
        }
    }
}

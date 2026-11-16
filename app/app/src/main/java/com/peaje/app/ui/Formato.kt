package com.peaje.app.ui

import java.text.NumberFormat
import java.util.Locale

private val formatoPesos: NumberFormat =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL")).apply { maximumFractionDigits = 0 }

/** 5000.0 -> "$5.000" */
fun formatearPesos(monto: Double): String = formatoPesos.format(monto)

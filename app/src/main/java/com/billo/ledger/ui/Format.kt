package com.billo.ledger.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatRupees(paise: Long): String {
    val sign = if (paise < 0) "-" else ""
    val abs = kotlin.math.abs(paise)
    val whole = abs / 100
    val frac = (abs % 100).toInt()
    return "$sign₹$whole" + if (frac != 0) "." + frac.toString().padStart(2, '0') else ""
}

fun formatWallTime(millis: Long): String =
    SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date(millis))

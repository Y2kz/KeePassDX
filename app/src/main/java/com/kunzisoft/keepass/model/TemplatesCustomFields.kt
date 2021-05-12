package com.kunzisoft.keepass.model

import android.content.Context
import com.kunzisoft.keepass.R

object TemplatesCustomFields {

    const val STANDARD_TITLE = "title"
    const val STANDARD_USERNAME = "username"
    const val STANDARD_PASSWORD = "password"
    const val STANDARD_URL = "url"
    const val STANDARD_EXPIRATION = "expires"
    const val STANDARD_NOTES = "notes"

    const val CREDIT_CARD_CARDHOLDER = "Card holder"
    const val CREDIT_CARD_NUMBER = "Number"
    const val CREDIT_CARD_EXPIRATION = "@exp_date"
    const val CREDIT_CARD_CVV = "CVV"
    private const val CREDIT_CARD_PIN = "PIN"

    fun isStandardFieldName(name: String): Boolean {
        return arrayOf(
                STANDARD_TITLE,
                STANDARD_USERNAME,
                STANDARD_PASSWORD,
                STANDARD_URL,
                STANDARD_EXPIRATION,
                STANDARD_NOTES
        ).firstOrNull { it.equals(name, true) } != null
    }

    fun getLocalizedName(context: Context?, fieldName: String): String {
        if (context == null)
            return fieldName
        return when (fieldName) {
            STANDARD_TITLE -> context.getString(R.string.entry_title)
            STANDARD_USERNAME -> context.getString(R.string.entry_user_name)
            STANDARD_PASSWORD -> context.getString(R.string.entry_password)
            STANDARD_URL -> context.getString(R.string.entry_url)
            STANDARD_EXPIRATION -> context.getString(R.string.entry_expires)
            STANDARD_NOTES -> context.getString(R.string.entry_notes)

            CREDIT_CARD_CARDHOLDER -> context.getString(R.string.credit_card_cardholder)
            CREDIT_CARD_NUMBER -> context.getString(R.string.credit_card_number)
            CREDIT_CARD_EXPIRATION -> context.getString(R.string.credit_card_expiration)
            CREDIT_CARD_CVV -> context.getString(R.string.credit_card_security_code)
            CREDIT_CARD_PIN -> context.getString(R.string.credit_card_pin)
            else -> fieldName
        }
    }
}
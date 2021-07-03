package com.kunzisoft.keepass.database.element.template

import com.kunzisoft.keepass.database.element.icon.IconImage
import com.kunzisoft.keepass.database.element.icon.IconImageStandard
import java.util.*

class TemplateBuilder {

    private val urlAttribute = TemplateAttribute(TemplateField.LABEL_URL, TemplateAttributeType.SINGLE_LINE)
    private val usernameAttribute = TemplateAttribute(TemplateField.LABEL_USERNAME, TemplateAttributeType.SINGLE_LINE)
    private val notesPlainAttribute = TemplateAttribute(TemplateField.LABEL_NOTES, TemplateAttributeType.MULTILINE)
    private val holderAttribute = TemplateAttribute(TemplateField.LABEL_HOLDER, TemplateAttributeType.SINGLE_LINE)
    private val numberAttribute = TemplateAttribute(TemplateField.LABEL_NUMBER, TemplateAttributeType.SINGLE_LINE)
    private val cvvAttribute = TemplateAttribute(TemplateField.LABEL_CVV, TemplateAttributeType.SINGLE_LINE, true)
    private val pinAttribute = TemplateAttribute(TemplateField.LABEL_PIN, TemplateAttributeType.SINGLE_LINE, true)
    private val nameAttribute = TemplateAttribute(TemplateField.LABEL_NAME, TemplateAttributeType.SINGLE_LINE)
    private val placeOfIssueAttribute = TemplateAttribute(TemplateField.LABEL_PLACE_OF_ISSUE, TemplateAttributeType.SINGLE_LINE)
    private val dateOfIssueAttribute = TemplateAttribute(TemplateField.LABEL_DATE_OF_ISSUE, TemplateAttributeType.DATE)
    private val expirationDateAttribute = TemplateAttribute(TemplateField.LABEL_EXPIRATION, TemplateAttributeType.DATE)
    private val emailAddressAttribute = TemplateAttribute(TemplateField.LABEL_EMAIL_ADDRESS, TemplateAttributeType.SINGLE_LINE)
    private val passwordAttribute = TemplateAttribute(TemplateField.LABEL_PASSWORD, TemplateAttributeType.SINGLE_LINE, true)
    private val ssidAttribute = TemplateAttribute(TemplateField.LABEL_SSID, TemplateAttributeType.SINGLE_LINE)
    private val tokenAttribute = TemplateAttribute(TemplateField.LABEL_TOKEN, TemplateAttributeType.SINGLE_LINE)
    private val publicKeyAttribute = TemplateAttribute(TemplateField.LABEL_PUBLIC_KEY, TemplateAttributeType.SINGLE_LINE)
    private val privateKeyAttribute = TemplateAttribute(TemplateField.LABEL_PRIVATE_KEY, TemplateAttributeType.SINGLE_LINE, true)
    private val seedAttribute = TemplateAttribute(TemplateField.LABEL_SEED, TemplateAttributeType.SINGLE_LINE, true)
    private val bicAttribute = TemplateAttribute(TemplateField.LABEL_BIC, TemplateAttributeType.SINGLE_LINE)
    private val ibanAttribute = TemplateAttribute(TemplateField.LABEL_IBAN, TemplateAttributeType.SINGLE_LINE)

    val email: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(emailAddressAttribute)
                add(urlAttribute)
                add(passwordAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID.randomUUID(),
                TemplateField.LABEL_EMAIL,
                IconImage(IconImageStandard(IconImageStandard.EMAIL_ID)),
                sections)
        }

    val wifi: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(ssidAttribute)
                add(passwordAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID.randomUUID(),
                TemplateField.LABEL_WIRELESS,
                IconImage(IconImageStandard(IconImageStandard.WIRELESS_ID)),
                sections)
        }

    val notes: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(notesPlainAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID.randomUUID(),
                TemplateField.LABEL_NOTES,
                IconImage(IconImageStandard(IconImageStandard.LIST_ID)),
                sections)
        }

    val idCard: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(numberAttribute)
                add(nameAttribute)
                add(placeOfIssueAttribute)
                add(dateOfIssueAttribute)
                add(expirationDateAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID.randomUUID(),
                TemplateField.LABEL_ID_CARD,
                IconImage(IconImageStandard(IconImageStandard.ID_CARD_ID)),
                sections)
        }

    val creditCard: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(numberAttribute)
                add(cvvAttribute)
                add(pinAttribute)
                add(holderAttribute)
                add(expirationDateAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID.randomUUID(),
                TemplateField.LABEL_DEBIT_CREDIT_CARD,
                IconImage(IconImageStandard(IconImageStandard.CREDIT_CARD_ID)),
                sections)
        }

    val bank: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(nameAttribute)
                add(urlAttribute)
                add(usernameAttribute)
                add(passwordAttribute)
            })
            val ibanSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(holderAttribute)
                add(bicAttribute)
                add(ibanAttribute)
            })
            sections.add(mainSection)
            sections.add(ibanSection)
            return Template(
                UUID.randomUUID(),
                TemplateField.LABEL_BANK,
                IconImage(IconImageStandard(IconImageStandard.DOLLAR_ID)),
                sections)
        }

    val cryptocurrency: Template
        get() {
            val sections = mutableListOf<TemplateSection>()
            val mainSection = TemplateSection(mutableListOf<TemplateAttribute>().apply {
                add(tokenAttribute)
                add(publicKeyAttribute)
                add(privateKeyAttribute)
                add(seedAttribute)
            })
            sections.add(mainSection)
            return Template(
                UUID.randomUUID(),
                TemplateField.LABEL_CRYPTOCURRENCY,
                IconImage(IconImageStandard(IconImageStandard.STAR_ID)),
                sections)
        }
}
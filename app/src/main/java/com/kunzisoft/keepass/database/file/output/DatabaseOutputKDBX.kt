/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *     
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.database.file.output

import android.util.Base64
import android.util.Log
import android.util.Xml
import com.kunzisoft.encrypt.StreamCipher
import com.kunzisoft.keepass.database.crypto.CrsAlgorithm
import com.kunzisoft.keepass.database.action.node.NodeHandler
import com.kunzisoft.keepass.database.crypto.CipherEngine
import com.kunzisoft.keepass.database.crypto.EncryptionAlgorithm
import com.kunzisoft.keepass.database.crypto.kdf.KdfFactory
import com.kunzisoft.keepass.database.element.DeletedObject
import com.kunzisoft.keepass.database.element.Tags
import com.kunzisoft.keepass.database.element.database.CompressionAlgorithm
import com.kunzisoft.keepass.database.element.database.DatabaseKDBX
import com.kunzisoft.keepass.database.element.database.DatabaseKDBX.Companion.BASE_64_FLAG
import com.kunzisoft.keepass.database.element.entry.AutoType
import com.kunzisoft.keepass.database.element.entry.EntryKDBX
import com.kunzisoft.keepass.database.element.group.GroupKDBX
import com.kunzisoft.keepass.database.element.node.NodeKDBXInterface
import com.kunzisoft.keepass.database.element.security.MemoryProtectionConfig
import com.kunzisoft.keepass.database.element.security.ProtectedString
import com.kunzisoft.keepass.database.exception.DatabaseOutputException
import com.kunzisoft.keepass.database.exception.UnknownKDF
import com.kunzisoft.keepass.database.file.DatabaseHeaderKDBX
import com.kunzisoft.keepass.database.file.DatabaseHeaderKDBX.Companion.FILE_VERSION_40
import com.kunzisoft.keepass.database.file.DatabaseKDBXXML
import com.kunzisoft.keepass.database.file.DateKDBXUtil
import com.kunzisoft.keepass.stream.HashedBlockOutputStream
import com.kunzisoft.keepass.stream.HmacBlockOutputStream
import com.kunzisoft.keepass.utils.*
import org.joda.time.DateTime
import org.xmlpull.v1.XmlSerializer
import java.io.IOException
import java.io.OutputStream
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.*
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import kotlin.experimental.or


class DatabaseOutputKDBX(private val mDatabaseKDBX: DatabaseKDBX,
                         outputStream: OutputStream)
    : DatabaseOutput<DatabaseHeaderKDBX>(outputStream) {

    private var randomStream: StreamCipher? = null
    private lateinit var xml: XmlSerializer
    private var header: DatabaseHeaderKDBX? = null
    private var hashOfHeader: ByteArray? = null
    private var headerHmac: ByteArray? = null
    private var engine: CipherEngine? = null

    @Throws(DatabaseOutputException::class)
    override fun output() {

        try {
            try {
                engine = EncryptionAlgorithm.getFrom(mDatabaseKDBX.cipherUuid).cipherEngine
            } catch (e: NoSuchAlgorithmException) {
                throw DatabaseOutputException("No such cipher", e)
            }

            header = outputHeader(mOutputStream)

            val osPlain: OutputStream = if (header!!.version.isBefore(FILE_VERSION_40)) {
                val cos = attachStreamEncryptor(header!!, mOutputStream)
                cos.write(header!!.streamStartBytes)

                HashedBlockOutputStream(cos)
            } else {
                mOutputStream.write(hashOfHeader!!)
                mOutputStream.write(headerHmac!!)

                attachStreamEncryptor(header!!, HmacBlockOutputStream(mOutputStream, mDatabaseKDBX.hmacKey!!))
            }

            val xmlOutputStream: OutputStream
            try {
                xmlOutputStream = when(mDatabaseKDBX.compressionAlgorithm) {
                    CompressionAlgorithm.GZip -> GZIPOutputStream(osPlain)
                    else -> osPlain
                }

                if (!header!!.version.isBefore(FILE_VERSION_40)) {
                    outputInnerHeader(mDatabaseKDBX, header!!, xmlOutputStream)
                }

                outputDatabase(xmlOutputStream)
                xmlOutputStream.close()
            } catch (e: IllegalArgumentException) {
                throw DatabaseOutputException(e)
            } catch (e: IllegalStateException) {
                throw DatabaseOutputException(e)
            }

        } catch (e: IOException) {
            throw DatabaseOutputException(e)
        }
    }

    @Throws(IOException::class)
    private fun outputInnerHeader(database: DatabaseKDBX,
                                  header: DatabaseHeaderKDBX,
                                  dataOutputStream: OutputStream) {
        dataOutputStream.writeByte(DatabaseHeaderKDBX.PwDbInnerHeaderV4Fields.InnerRandomStreamID)
        dataOutputStream.write4BytesUInt(UnsignedInt(4))
        if (header.innerRandomStream == null)
            throw IOException("Can't write innerRandomStream")
        dataOutputStream.write4BytesUInt(header.innerRandomStream!!.id)

        val streamKeySize = header.innerRandomStreamKey.size
        dataOutputStream.writeByte(DatabaseHeaderKDBX.PwDbInnerHeaderV4Fields.InnerRandomstreamKey)
        dataOutputStream.write4BytesUInt(UnsignedInt(streamKeySize))
        dataOutputStream.write(header.innerRandomStreamKey)

        val binaryCache = database.binaryCache
        database.attachmentPool.doForEachOrderedBinaryWithoutDuplication { _, binary ->
            // Force decompression to add binary in header
            binary.decompress(binaryCache)
            // Write type binary
            dataOutputStream.writeByte(DatabaseHeaderKDBX.PwDbInnerHeaderV4Fields.Binary)
            // Write size
            dataOutputStream.write4BytesUInt(UnsignedInt.fromKotlinLong(binary.getSize() + 1))
            // Write protected flag
            var flag = DatabaseHeaderKDBX.KdbxBinaryFlags.None
            if (binary.isProtected) {
                flag = flag or DatabaseHeaderKDBX.KdbxBinaryFlags.Protected
            }
            dataOutputStream.writeByte(flag)

            binary.getInputDataStream(binaryCache).use { inputStream ->
                inputStream.readAllBytes { buffer ->
                    dataOutputStream.write(buffer)
                }
            }
        }

        dataOutputStream.writeByte(DatabaseHeaderKDBX.PwDbInnerHeaderV4Fields.EndOfHeader)
        dataOutputStream.write4BytesUInt(UnsignedInt(0))
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun outputDatabase(outputStream: OutputStream) {

        xml = Xml.newSerializer()

        xml.setOutput(outputStream, "UTF-8")
        xml.startDocument("UTF-8", true)

        xml.startTag(null, DatabaseKDBXXML.ElemDocNode)

        writeMeta()

        mDatabaseKDBX.rootGroup?.let { root ->
            xml.startTag(null, DatabaseKDBXXML.ElemRoot)
            startGroup(root)
            val groupStack = Stack<GroupKDBX>()
            groupStack.push(root)

            if (!root.doForEachChild(
                            object : NodeHandler<EntryKDBX>() {
                                override fun operate(node: EntryKDBX): Boolean {
                                    try {
                                        writeEntry(node, false)
                                    } catch (ex: IOException) {
                                        throw RuntimeException(ex)
                                    }

                                    return true
                                }
                            },
                            object : NodeHandler<GroupKDBX>() {
                                override fun operate(node: GroupKDBX): Boolean {
                                    while (true) {
                                        try {
                                            if (node.parent === groupStack.peek()) {
                                                groupStack.push(node)
                                                startGroup(node)
                                                break
                                            } else {
                                                groupStack.pop()
                                                if (groupStack.size <= 0) return false
                                                endGroup()
                                            }
                                        } catch (e: IOException) {
                                            throw RuntimeException(e)
                                        }

                                    }
                                    return true
                                }
                            })
            )
                throw RuntimeException("Writing groups failed")

            while (groupStack.size > 1) {
                xml.endTag(null, DatabaseKDBXXML.ElemGroup)
                groupStack.pop()
            }
        }

        endGroup()

        writeDeletedObjects(mDatabaseKDBX.deletedObjects)

        xml.endTag(null, DatabaseKDBXXML.ElemRoot)

        xml.endTag(null, DatabaseKDBXXML.ElemDocNode)
        xml.endDocument()
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeMeta() {
        xml.startTag(null, DatabaseKDBXXML.ElemMeta)

        writeObject(DatabaseKDBXXML.ElemGenerator, mDatabaseKDBX.localizedAppName)

        if (hashOfHeader != null) {
            writeObject(DatabaseKDBXXML.ElemHeaderHash, String(Base64.encode(hashOfHeader!!, BASE_64_FLAG)))
        }

        writeObject(DatabaseKDBXXML.ElemDbName, mDatabaseKDBX.name, true)
        writeObject(DatabaseKDBXXML.ElemDbNameChanged, mDatabaseKDBX.nameChanged.date)
        writeObject(DatabaseKDBXXML.ElemDbDesc, mDatabaseKDBX.description, true)
        writeObject(DatabaseKDBXXML.ElemDbDescChanged, mDatabaseKDBX.descriptionChanged.date)
        writeObject(DatabaseKDBXXML.ElemDbDefaultUser, mDatabaseKDBX.defaultUserName, true)
        writeObject(DatabaseKDBXXML.ElemDbDefaultUserChanged, mDatabaseKDBX.defaultUserNameChanged.date)
        writeObject(DatabaseKDBXXML.ElemDbMntncHistoryDays, mDatabaseKDBX.maintenanceHistoryDays.toKotlinLong())
        writeObject(DatabaseKDBXXML.ElemDbColor, mDatabaseKDBX.color)
        writeObject(DatabaseKDBXXML.ElemDbKeyChanged, mDatabaseKDBX.keyLastChanged.date)
        writeObject(DatabaseKDBXXML.ElemDbKeyChangeRec, mDatabaseKDBX.keyChangeRecDays)
        writeObject(DatabaseKDBXXML.ElemDbKeyChangeForce, mDatabaseKDBX.keyChangeForceDays)

        writeMemoryProtection(mDatabaseKDBX.memoryProtection)

        writeCustomIconList()

        writeObject(DatabaseKDBXXML.ElemRecycleBinEnabled, mDatabaseKDBX.isRecycleBinEnabled)
        writeUuid(DatabaseKDBXXML.ElemRecycleBinUuid, mDatabaseKDBX.recycleBinUUID)
        writeObject(DatabaseKDBXXML.ElemRecycleBinChanged, mDatabaseKDBX.recycleBinChanged)
        writeUuid(DatabaseKDBXXML.ElemEntryTemplatesGroup, mDatabaseKDBX.entryTemplatesGroup)
        writeObject(DatabaseKDBXXML.ElemEntryTemplatesGroupChanged, mDatabaseKDBX.entryTemplatesGroupChanged.date)
        writeObject(DatabaseKDBXXML.ElemHistoryMaxItems, mDatabaseKDBX.historyMaxItems.toLong())
        writeObject(DatabaseKDBXXML.ElemHistoryMaxSize, mDatabaseKDBX.historyMaxSize)
        writeUuid(DatabaseKDBXXML.ElemLastSelectedGroup, mDatabaseKDBX.lastSelectedGroupUUID)
        writeUuid(DatabaseKDBXXML.ElemLastTopVisibleGroup, mDatabaseKDBX.lastTopVisibleGroupUUID)

        // Seem to work properly if always in meta
        if (header!!.version.isBefore(FILE_VERSION_40))
            writeMetaBinaries()

        writeCustomData(mDatabaseKDBX.customData)

        xml.endTag(null, DatabaseKDBXXML.ElemMeta)
    }

    @Throws(DatabaseOutputException::class)
    private fun attachStreamEncryptor(header: DatabaseHeaderKDBX, os: OutputStream): CipherOutputStream {
        val cipher: Cipher
        try {
            cipher = engine!!.getCipher(Cipher.ENCRYPT_MODE, mDatabaseKDBX.finalKey!!, header.encryptionIV)
        } catch (e: Exception) {
            throw DatabaseOutputException("Invalid algorithm.", e)
        }

        return CipherOutputStream(os, cipher)
    }

    @Throws(DatabaseOutputException::class)
    override fun setIVs(header: DatabaseHeaderKDBX): SecureRandom {
        val random = super.setIVs(header)
        random.nextBytes(header.masterSeed)

        val ivLength = engine!!.ivLength()
        if (ivLength != header.encryptionIV.size) {
            header.encryptionIV = ByteArray(ivLength)
        }
        random.nextBytes(header.encryptionIV)

        if (mDatabaseKDBX.kdfParameters == null) {
            mDatabaseKDBX.kdfParameters = KdfFactory.aesKdf.defaultParameters
        }

        try {
            val kdf = mDatabaseKDBX.getEngineKDBX4(mDatabaseKDBX.kdfParameters)
            kdf.randomize(mDatabaseKDBX.kdfParameters!!)
        } catch (unknownKDF: UnknownKDF) {
            Log.e(TAG, "Unable to retrieve header", unknownKDF)
        }

        if (header.version.isBefore(FILE_VERSION_40)) {
            header.innerRandomStream = CrsAlgorithm.Salsa20
            header.innerRandomStreamKey = ByteArray(32)
        } else {
            header.innerRandomStream = CrsAlgorithm.ChaCha20
            header.innerRandomStreamKey = ByteArray(64)
        }
        random.nextBytes(header.innerRandomStreamKey)

        try {
            randomStream = CrsAlgorithm.getCipher(header.innerRandomStream, header.innerRandomStreamKey)
        } catch (e: Exception) {
            throw DatabaseOutputException(e)
        }

        if (header.version.isBefore(FILE_VERSION_40)) {
            random.nextBytes(header.streamStartBytes)
        }

        return random
    }

    @Throws(DatabaseOutputException::class)
    override fun outputHeader(outputStream: OutputStream): DatabaseHeaderKDBX {
        try {
            val header = DatabaseHeaderKDBX(mDatabaseKDBX)
            setIVs(header)

            val pho = DatabaseHeaderOutputKDBX(mDatabaseKDBX, header, outputStream)
            pho.output()

            hashOfHeader = pho.hashOfHeader
            headerHmac = pho.headerHmac

            return header
        } catch (e: IOException) {
            throw DatabaseOutputException("Failed to output the header.", e)
        }
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun startGroup(group: GroupKDBX) {
        xml.startTag(null, DatabaseKDBXXML.ElemGroup)
        writeUuid(DatabaseKDBXXML.ElemUuid, group.id)
        writeObject(DatabaseKDBXXML.ElemName, group.title)
        writeObject(DatabaseKDBXXML.ElemNotes, group.notes)
        writeObject(DatabaseKDBXXML.ElemIcon, group.icon.standard.id.toLong())

        if (!group.icon.custom.isUnknown) {
            writeUuid(DatabaseKDBXXML.ElemCustomIconID, group.icon.custom.uuid)
        }

        writeTags(group.tags)
        writeTimes(group)
        writeObject(DatabaseKDBXXML.ElemIsExpanded, group.isExpanded)
        writeObject(DatabaseKDBXXML.ElemGroupDefaultAutoTypeSeq, group.defaultAutoTypeSequence)
        writeObject(DatabaseKDBXXML.ElemEnableAutoType, group.enableAutoType)
        writeObject(DatabaseKDBXXML.ElemEnableSearching, group.enableSearching)
        writeUuid(DatabaseKDBXXML.ElemLastTopVisibleEntry, group.lastTopVisibleEntry)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun endGroup() {
        xml.endTag(null, DatabaseKDBXXML.ElemGroup)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeEntry(entry: EntryKDBX, isHistory: Boolean) {

        xml.startTag(null, DatabaseKDBXXML.ElemEntry)

        writeUuid(DatabaseKDBXXML.ElemUuid, entry.id)
        writeObject(DatabaseKDBXXML.ElemIcon, entry.icon.standard.id.toLong())

        if (!entry.icon.custom.isUnknown) {
            writeUuid(DatabaseKDBXXML.ElemCustomIconID, entry.icon.custom.uuid)
        }

        writeObject(DatabaseKDBXXML.ElemFgColor, entry.foregroundColor)
        writeObject(DatabaseKDBXXML.ElemBgColor, entry.backgroundColor)
        writeObject(DatabaseKDBXXML.ElemOverrideUrl, entry.overrideURL)

        // Write quality check only if false
        if (!entry.qualityCheck) {
            writeObject(DatabaseKDBXXML.ElemQualityCheck, entry.qualityCheck)
        }
        writeTags(entry.tags)
        writeTimes(entry)
        writeFields(entry.fields)
        writeEntryBinaries(entry.binaries)
        if (entry.containsCustomData()) {
            writeCustomData(entry.customData)
        }
        writeAutoType(entry.autoType)

        if (!isHistory) {
            writeEntryHistory(entry.history)
        }

        xml.endTag(null, DatabaseKDBXXML.ElemEntry)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeObject(name: String, value: String, filterXmlChars: Boolean = false) {
        var xmlString = value

        xml.startTag(null, name)

        if (filterXmlChars) {
            xmlString = safeXmlString(xmlString)
        }

        xml.text(xmlString)
        xml.endTag(null, name)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeObject(name: String, value: Date) {
        if (header!!.version.isBefore(FILE_VERSION_40)) {
            writeObject(name, DatabaseKDBXXML.DateFormatter.format(value))
        } else {
            val dt = DateTime(value)
            val seconds = DateKDBXUtil.convertDateToKDBX4Time(dt)
            val buf = longTo8Bytes(seconds)
            val b64 = String(Base64.encode(buf, BASE_64_FLAG))
            writeObject(name, b64)
        }
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeObject(name: String, value: Long) {
        writeObject(name, value.toString())
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeObject(name: String, value: Boolean?) {
        val text: String = when {
            value == null -> DatabaseKDBXXML.ValNull
            value -> DatabaseKDBXXML.ValTrue
            else -> DatabaseKDBXXML.ValFalse
        }

        writeObject(name, text)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeUuid(name: String, uuid: UUID) {
        val data = uuidTo16Bytes(uuid)
        writeObject(name, String(Base64.encode(data, BASE_64_FLAG)))
    }

    /*
    // Normally used by a single entry but obsolete because binaries are in meta tag with kdbx3.1-
    // or in file header with kdbx4
    // binary.isProtected attribute is not used to create the XML
    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeEntryBinary(binary : BinaryAttachment) {
        if (binary.length() > 0) {
            if (binary.isProtected) {
                xml.attribute(null, DatabaseKDBXXML.AttrProtected, DatabaseKDBXXML.ValTrue)
                binary.getInputDataStream().use { inputStream ->
                    inputStream.readBytes { buffer ->
                        val encoded = ByteArray(buffer.size)
                        randomStream!!.processBytes(buffer, 0, encoded.size, encoded, 0)
                        xml.text(String(Base64.encode(encoded, BASE_64_FLAG)))
                    }
                }
            } else {
                // Write the XML
                binary.getInputDataStream().use { inputStream ->
                    inputStream.readBytes { buffer ->
                        xml.text(String(Base64.encode(buffer, BASE_64_FLAG)))
                    }
                }
            }
        }
    }
    */

    // Only uses with kdbx3.1 to write binaries in meta tag
    // With kdbx4, don't use this method because binaries are in header file
    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeMetaBinaries() {
        xml.startTag(null, DatabaseKDBXXML.ElemBinaries)
        // Use indexes because necessarily (binary header ref is the order)
        val binaryCache = mDatabaseKDBX.binaryCache
        mDatabaseKDBX.attachmentPool.doForEachOrderedBinaryWithoutDuplication { index, binary ->
            xml.startTag(null, DatabaseKDBXXML.ElemBinary)
            xml.attribute(null, DatabaseKDBXXML.AttrId, index.toString())
            if (binary.getSize() > 0) {
                if (binary.isCompressed) {
                    xml.attribute(null, DatabaseKDBXXML.AttrCompressed, DatabaseKDBXXML.ValTrue)
                }
                try {
                    // Write the XML
                    binary.getInputDataStream(binaryCache).use { inputStream ->
                        inputStream.readAllBytes { buffer ->
                            xml.text(String(Base64.encode(buffer, BASE_64_FLAG)))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to write binary", e)
                }
            }
            xml.endTag(null, DatabaseKDBXXML.ElemBinary)
        }
        xml.endTag(null, DatabaseKDBXXML.ElemBinaries)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeObject(name: String, keyName: String, keyValue: String, valueName: String, valueValue: String) {
        xml.startTag(null, name)

        xml.startTag(null, keyName)
        xml.text(safeXmlString(keyValue))
        xml.endTag(null, keyName)

        xml.startTag(null, valueName)
        xml.text(safeXmlString(valueValue))
        xml.endTag(null, valueName)

        xml.endTag(null, name)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeAutoType(autoType: AutoType) {
        xml.startTag(null, DatabaseKDBXXML.ElemAutoType)

        writeObject(DatabaseKDBXXML.ElemAutoTypeEnabled, autoType.enabled)
        writeObject(DatabaseKDBXXML.ElemAutoTypeObfuscation, autoType.obfuscationOptions.toKotlinLong())

        if (autoType.defaultSequence.isNotEmpty()) {
            writeObject(DatabaseKDBXXML.ElemAutoTypeDefaultSeq, autoType.defaultSequence, true)
        }

        for ((key, value) in autoType.entrySet()) {
            writeObject(DatabaseKDBXXML.ElemAutoTypeItem, DatabaseKDBXXML.ElemWindow, key, DatabaseKDBXXML.ElemKeystrokeSequence, value)
        }

        xml.endTag(null, DatabaseKDBXXML.ElemAutoType)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeFields(fields: Map<String, ProtectedString>) {

        for ((key, value) in fields) {
            writeField(key, value)
        }
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeField(key: String, value: ProtectedString) {

        xml.startTag(null, DatabaseKDBXXML.ElemString)
        xml.startTag(null, DatabaseKDBXXML.ElemKey)
        xml.text(safeXmlString(key))
        xml.endTag(null, DatabaseKDBXXML.ElemKey)

        xml.startTag(null, DatabaseKDBXXML.ElemValue)
        var protect = value.isProtected

        when (key) {
            MemoryProtectionConfig.ProtectDefinition.TITLE_FIELD -> protect = mDatabaseKDBX.memoryProtection.protectTitle
            MemoryProtectionConfig.ProtectDefinition.USERNAME_FIELD -> protect = mDatabaseKDBX.memoryProtection.protectUserName
            MemoryProtectionConfig.ProtectDefinition.PASSWORD_FIELD -> protect = mDatabaseKDBX.memoryProtection.protectPassword
            MemoryProtectionConfig.ProtectDefinition.URL_FIELD -> protect = mDatabaseKDBX.memoryProtection.protectUrl
            MemoryProtectionConfig.ProtectDefinition.NOTES_FIELD -> protect = mDatabaseKDBX.memoryProtection.protectNotes
        }

        if (protect) {
            xml.attribute(null, DatabaseKDBXXML.AttrProtected, DatabaseKDBXXML.ValTrue)
            val data = value.toString().toByteArray()
            val encoded = randomStream?.processBytes(data) ?: ByteArray(0)
            xml.text(String(Base64.encode(encoded, BASE_64_FLAG)))
        } else {
            xml.text(value.toString())
        }

        xml.endTag(null, DatabaseKDBXXML.ElemValue)
        xml.endTag(null, DatabaseKDBXXML.ElemString)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeDeletedObject(value: DeletedObject) {
        xml.startTag(null, DatabaseKDBXXML.ElemDeletedObject)

        writeUuid(DatabaseKDBXXML.ElemUuid, value.uuid)
        writeObject(DatabaseKDBXXML.ElemDeletionTime, value.getDeletionTime())

        xml.endTag(null, DatabaseKDBXXML.ElemDeletedObject)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeEntryBinaries(binaries: LinkedHashMap<String, Int>) {
        for ((label, poolId) in binaries) {
            // Retrieve the right index with the poolId, don't use ref because of header in DatabaseV4
            mDatabaseKDBX.attachmentPool.getBinaryIndexFromKey(poolId)?.toString()?.let { indexString ->
                xml.startTag(null, DatabaseKDBXXML.ElemBinary)
                xml.startTag(null, DatabaseKDBXXML.ElemKey)
                xml.text(safeXmlString(label))
                xml.endTag(null, DatabaseKDBXXML.ElemKey)

                xml.startTag(null, DatabaseKDBXXML.ElemValue)
                // Use only pool data in Meta to save binaries
                xml.attribute(null, DatabaseKDBXXML.AttrRef, indexString)
                xml.endTag(null, DatabaseKDBXXML.ElemValue)

                xml.endTag(null, DatabaseKDBXXML.ElemBinary)
            }
        }
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeDeletedObjects(value: List<DeletedObject>) {
        xml.startTag(null, DatabaseKDBXXML.ElemDeletedObjects)

        for (pdo in value) {
            writeDeletedObject(pdo)
        }

        xml.endTag(null, DatabaseKDBXXML.ElemDeletedObjects)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeMemoryProtection(value: MemoryProtectionConfig) {
        xml.startTag(null, DatabaseKDBXXML.ElemMemoryProt)

        writeObject(DatabaseKDBXXML.ElemProtTitle, value.protectTitle)
        writeObject(DatabaseKDBXXML.ElemProtUserName, value.protectUserName)
        writeObject(DatabaseKDBXXML.ElemProtPassword, value.protectPassword)
        writeObject(DatabaseKDBXXML.ElemProtURL, value.protectUrl)
        writeObject(DatabaseKDBXXML.ElemProtNotes, value.protectNotes)

        xml.endTag(null, DatabaseKDBXXML.ElemMemoryProt)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeCustomData(customData: Map<String, String>) {
        xml.startTag(null, DatabaseKDBXXML.ElemCustomData)

        for ((key, value) in customData) {
            writeObject(
                    DatabaseKDBXXML.ElemStringDictExItem,
                    DatabaseKDBXXML.ElemKey,
                    key,
                    DatabaseKDBXXML.ElemValue,
                    value
            )
        }

        xml.endTag(null, DatabaseKDBXXML.ElemCustomData)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeTags(tags: Tags) {
        if (!tags.isEmpty()) {
            writeObject(DatabaseKDBXXML.ElemTags, tags.toString())
        }
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeTimes(node: NodeKDBXInterface) {
        xml.startTag(null, DatabaseKDBXXML.ElemTimes)

        writeObject(DatabaseKDBXXML.ElemLastModTime, node.lastModificationTime.date)
        writeObject(DatabaseKDBXXML.ElemCreationTime, node.creationTime.date)
        writeObject(DatabaseKDBXXML.ElemLastAccessTime, node.lastAccessTime.date)
        writeObject(DatabaseKDBXXML.ElemExpiryTime, node.expiryTime.date)
        writeObject(DatabaseKDBXXML.ElemExpires, node.expires)
        writeObject(DatabaseKDBXXML.ElemUsageCount, node.usageCount.toKotlinLong())
        writeObject(DatabaseKDBXXML.ElemLocationChanged, node.locationChanged.date)

        xml.endTag(null, DatabaseKDBXXML.ElemTimes)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeEntryHistory(value: List<EntryKDBX>) {
        val element = DatabaseKDBXXML.ElemHistory

        xml.startTag(null, element)

        for (entry in value) {
            writeEntry(entry, true)
        }

        xml.endTag(null, element)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeCustomIconList() {
        var firstElement = true
        val binaryCache = mDatabaseKDBX.binaryCache
        mDatabaseKDBX.iconsManager.doForEachCustomIcon { iconCustom, binary ->
            if (binary.dataExists()) {
                // Write the parent tag
                if (firstElement) {
                    xml.startTag(null, DatabaseKDBXXML.ElemCustomIcons)
                    firstElement = false
                }

                xml.startTag(null, DatabaseKDBXXML.ElemCustomIconItem)

                writeUuid(DatabaseKDBXXML.ElemCustomIconItemID, iconCustom.uuid)
                var customImageData = ByteArray(0)
                try {
                    binary.getInputDataStream(binaryCache).use { inputStream ->
                        customImageData = inputStream.readBytes()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to write custom icon", e)
                } finally {
                    writeObject(DatabaseKDBXXML.ElemCustomIconItemData,
                            String(Base64.encode(customImageData, BASE_64_FLAG)))
                }

                xml.endTag(null, DatabaseKDBXXML.ElemCustomIconItem)
            }
        }
        // Close the parent tag
        if (!firstElement) {
            xml.endTag(null, DatabaseKDBXXML.ElemCustomIcons)
        }
    }

    private fun safeXmlString(text: String): String {
        if (text.isEmpty()) {
            return text
        }
        val stringBuilder = StringBuilder()
        var character: Char
        for (element in text) {
            character = element
            val hexChar = character.toInt()
            if (
                    hexChar in 0x20..0xD7FF ||
                    hexChar == 0x9 ||
                    hexChar == 0xA ||
                    hexChar == 0xD ||
                    hexChar in 0xE000..0xFFFD
            ) {
                stringBuilder.append(character)
            }
        }
        return stringBuilder.toString()
    }

    companion object {
        private val TAG = DatabaseOutputKDBX::class.java.name
    }
}

package com.example.data.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.domain.classifier.PhoneAndSenderNormalizer
import java.util.Locale

data class SimpleContact(
    val displayName: String,
    val phoneNumber: String
)

/**
 * In-memory cached contact lookup helper using `ContactsContract` so 500-message
 * batch SMS imports resolve saved contacts in O(1) without N+1 IPC overhead.
 */
class ContactLookupCache(private val context: Context) {

    private val lock = Any()
    private var phoneToNameMap: Map<String, String> = emptyMap()
    private var allContactsList: List<SimpleContact> = emptyList()
    private var cacheLoadedAtMillis: Long = 0L

    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun warmCache(forceRefresh: Boolean = false) {
        if (!hasContactsPermission()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (!forceRefresh && phoneToNameMap.isNotEmpty() && (now - cacheLoadedAtMillis) < 60_000L) {
                return
            }
        }

        val freshMap = HashMap<String, String>()
        val contacts = ArrayList<SimpleContact>()

        runCatching {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)?.trim().orEmpty()
                    val number = cursor.getString(numIdx)?.trim().orEmpty()
                    if (name.isNotEmpty() && number.isNotEmpty()) {
                        PhoneAndSenderNormalizer.contactMatchKey(number)?.let { key ->
                            freshMap.putIfAbsent(key, name)
                        }
                        contacts.add(SimpleContact(displayName = name, phoneNumber = number))
                    }
                }
            }
        }

        synchronized(lock) {
            phoneToNameMap = freshMap
            allContactsList = contacts.distinctBy { "${it.displayName}_${it.phoneNumber}" }
            cacheLoadedAtMillis = now
        }
    }

    fun resolveContactName(rawAddress: String): String? {
        if (!hasContactsPermission()) return null
        val matchKey = PhoneAndSenderNormalizer.contactMatchKey(rawAddress) ?: return null

        synchronized(lock) {
            if (phoneToNameMap.isNotEmpty()) {
                phoneToNameMap[matchKey]?.let { return it }
            }
        }

        // Direct fallback via ContactsContract.PhoneLookup for single real-time incoming SMS
        return runCatching {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(rawAddress)
            )
            context.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    fun searchContacts(query: String): List<SimpleContact> {
        if (!hasContactsPermission()) return emptyList()
        warmCache(forceRefresh = false)
        val q = query.trim().lowercase(Locale.ROOT)
        val qDigits = q.filter { it.isDigit() }
        val matchNormalizedDigits = qDigits.isNotEmpty() && q.none { it.isLetter() }
        synchronized(lock) {
            if (q.isEmpty()) return allContactsList.take(25)
            return allContactsList.filter { contact ->
                contact.displayName.lowercase(Locale.ROOT).contains(q) ||
                    contact.phoneNumber.lowercase(Locale.ROOT).contains(q) ||
                    (matchNormalizedDigits && contact.phoneNumber.filter { it.isDigit() }.contains(qDigits))
            }.take(20)
        }
    }
}

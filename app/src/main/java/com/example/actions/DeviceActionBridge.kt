package com.example.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Android action bridge executing safe, allowlisted device actions
 * requested by Gemini via tool calling.
 */
class DeviceActionBridge(private val context: Context) {

    companion object {
        private const val TAG = "TejasActionBridge"
    }

    /**
     * Open WhatsApp via application intent or deep link.
     */
    fun openWhatsApp(): JSONObject {
        val result = JSONObject()
        result.put("action", "openWhatsApp")
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage("com.whatsapp")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                result.put("success", true)
                result.put("message", "WhatsApp launched successfully on device")
            } else {
                // Try deep link
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send"))
                uriIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (uriIntent.resolveActivity(pm) != null) {
                    context.startActivity(uriIntent)
                    result.put("success", true)
                    result.put("message", "WhatsApp opened via deep link")
                } else {
                    // Fallback to web
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://web.whatsapp.com"))
                    webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(webIntent)
                    result.put("success", true)
                    result.put("message", "WhatsApp Web opened in browser as fallback")
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error opening WhatsApp", e)
            result.put("success", false)
            result.put("error", "Could not open WhatsApp: ${e.message}")
            result
        }
    }

    /**
     * Open supported allowlisted application.
     */
    fun openApp(appName: String): JSONObject {
        val result = JSONObject()
        result.put("action", "openApp")
        result.put("requestedApp", appName)

        val cleanName = appName.trim().lowercase()
        return try {
            val pm = context.packageManager
            when {
                cleanName.contains("whatsapp") -> {
                    openWhatsApp()
                }
                cleanName.contains("youtube") -> {
                    val intent = pm.getLaunchIntentForPackage("com.google.android.youtube")
                        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    result.put("success", true)
                    result.put("message", "YouTube opened successfully")
                    result
                }
                cleanName.contains("instagram") -> {
                    val intent = pm.getLaunchIntentForPackage("com.instagram.android")
                        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    result.put("success", true)
                    result.put("message", "Instagram opened successfully")
                    result
                }
                cleanName.contains("spotify") -> {
                    val intent = pm.getLaunchIntentForPackage("com.spotify.music")
                        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    result.put("success", true)
                    result.put("message", "Spotify opened successfully")
                    result
                }
                cleanName.contains("camera") -> {
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(pm) != null) {
                        context.startActivity(intent)
                        result.put("success", true)
                        result.put("message", "Camera opened successfully")
                    } else {
                        result.put("success", false)
                        result.put("error", "Camera application not available")
                    }
                    result
                }
                cleanName.contains("map") || cleanName.contains("maps") -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    result.put("success", true)
                    result.put("message", "Google Maps opened successfully")
                    result
                }
                cleanName.contains("browser") || cleanName.contains("chrome") -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    result.put("success", true)
                    result.put("message", "Web browser opened successfully")
                    result
                }
                cleanName.contains("setting") -> {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    result.put("success", true)
                    result.put("message", "Device Settings opened")
                    result
                }
                else -> {
                    result.put("success", false)
                    result.put(
                        "error",
                        "App '$appName' is not in the safe allowlist. Supported apps: WhatsApp, YouTube, Instagram, Spotify, Camera, Maps, Browser, Settings."
                    )
                    result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app: $appName", e)
            result.put("success", false)
            result.put("error", "Failed to open $appName: ${e.message}")
            result
        }
    }

    /**
     * Open validated website URL in the browser.
     */
    fun openUrl(url: String): JSONObject {
        val result = JSONObject()
        result.put("action", "openUrl")
        var validUrl = url.trim()
        if (!validUrl.startsWith("http://") && !validUrl.startsWith("https://")) {
            validUrl = "https://$validUrl"
        }

        return try {
            val uri = Uri.parse(validUrl)
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            result.put("success", true)
            result.put("url", validUrl)
            result.put("message", "Opened $validUrl in browser")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error opening URL: $url", e)
            result.put("success", false)
            result.put("error", "Invalid or unsupported URL: ${e.message}")
            result
        }
    }

    /**
     * Make a phone call or open dialer with the phone number.
     */
    fun makeCall(phoneNumber: String): JSONObject {
        val result = JSONObject()
        result.put("action", "makeCall")
        val cleanNumber = phoneNumber.filter { it.isDigit() || it == '+' }

        if (cleanNumber.isBlank()) {
            result.put("success", false)
            result.put("error", "Invalid phone number provided")
            return result
        }

        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            result.put("success", true)
            result.put("phoneNumber", cleanNumber)
            result.put("message", "Opened phone dialer with $cleanNumber")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error making call to: $phoneNumber", e)
            result.put("success", false)
            result.put("error", "Could not start call action: ${e.message}")
            result
        }
    }

    /**
     * Search contacts by name and initiate call or dialer action.
     */
    fun callContact(contactName: String): JSONObject {
        val result = JSONObject()
        result.put("action", "callContact")
        result.put("searchedName", contactName)

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            result.put("success", false)
            result.put("permissionRequired", true)
            result.put("error", "Contacts permission is required to search contacts by name.")
            return result
        }

        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$contactName%")

            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            data class ContactMatch(val name: String, val number: String)
            val matches = mutableListOf<ContactMatch>()

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = if (nameIndex >= 0) it.getString(nameIndex) ?: "" else ""
                    val number = if (numberIndex >= 0) it.getString(numberIndex) ?: "" else ""
                    if (name.isNotBlank() && number.isNotBlank()) {
                        matches.add(ContactMatch(name, number))
                    }
                }
            }

            // Deduplicate by name and number
            val uniqueMatches = matches.distinctBy { "${it.name}_${it.number.filter { c -> c.isDigit() }}" }

            when {
                uniqueMatches.isEmpty() -> {
                    result.put("success", false)
                    result.put("error", "No contact found matching '$contactName'.")
                }
                uniqueMatches.size == 1 -> {
                    val contact = uniqueMatches.first()
                    val dialResult = makeCall(contact.number)
                    result.put("success", dialResult.getBoolean("success"))
                    result.put("contactName", contact.name)
                    result.put("phoneNumber", contact.number)
                    result.put("message", "Calling ${contact.name} at ${contact.number}")
                }
                else -> {
                    // Multiple contacts found: do NOT guess, return names so Tejas asks user
                    result.put("success", false)
                    result.put("multipleMatches", true)
                    val namesArray = JSONArray()
                    uniqueMatches.forEach {
                        val cObj = JSONObject()
                        cObj.put("name", it.name)
                        cObj.put("number", it.number)
                        namesArray.put(cObj)
                    }
                    result.put("contacts", namesArray)
                    result.put(
                        "error",
                        "Found ${uniqueMatches.size} contacts named '$contactName' (${uniqueMatches.joinToString { it.name }}). Please ask the user which one they would like to call."
                    )
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts for: $contactName", e)
            result.put("success", false)
            result.put("error", "Failed to access contacts: ${e.message}")
            result
        }
    }
}

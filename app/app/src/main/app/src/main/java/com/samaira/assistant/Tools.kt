package com.samaira.assistant

import android.app.Activity
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The set of actions Samaira can perform on the phone.
 * These use safe Android intents: for calls and messages the phone's own
 * dialer / messaging app opens pre-filled, so YOU tap the final button.
 */
object Tools {

    /** JSON schema of every tool, sent to the model. */
    val definitions: JSONArray by lazy { JSONArray(TOOLS_JSON) }

    fun execute(act: Activity, name: String, args: JSONObject): String {
        return try {
            when (name) {
                "make_call" -> {
                    val phone = args.getString("phone")
                    start(act, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                    "Opened dialer for $phone. User will press call."
                }
                "send_message" -> {
                    val appName = args.optString("app", "sms")
                    val phone = args.optString("phone", "")
                    val textMsg = args.optString("text", "")
                    if (appName.equals("whatsapp", true)) {
                        val num = phone.replace("+", "").replace(" ", "")
                        val uri = Uri.parse("https://wa.me/$num?text=" + Uri.encode(textMsg))
                        start(act, Intent(Intent.ACTION_VIEW, uri))
                        "Opened WhatsApp to $phone, pre-filled. User will press send."
                    } else {
                        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
                        i.putExtra("sms_body", textMsg)
                        start(act, i)
                        "Opened SMS to $phone, pre-filled. User will press send."
                    }
                }
                "open_app" -> {
                    val query = args.getString("query")
                    openApp(act, query)
                }
                "web_search" -> {
                    val q = args.getString("query")
                    start(act, Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
                    "Opened a web search for '$q'."
                }
                "set_alarm" -> {
                    val hour = args.getInt("hour")
                    val minute = args.optInt("minute", 0)
                    val label = args.optString("message", "Alarm")
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    start(act, i)
                    "Alarm set for %02d:%02d.".format(hour, minute)
                }
                "set_timer" -> {
                    val seconds = args.getInt("seconds")
                    val label = args.optString("message", "Timer")
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    start(act, i)
                    "Timer started for $seconds seconds."
                }
                "open_maps" -> {
                    val dest = args.getString("destination")
                    val uri = Uri.parse("google.navigation:q=" + Uri.encode(dest))
                    val i = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
                    try {
                        start(act, i)
                    } catch (e: Exception) {
                        start(act, Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(dest))))
                    }
                    "Started navigation to $dest."
                }
                "flashlight" -> {
                    val on = args.optBoolean("on", true)
                    toggleTorch(act, on)
                    if (on) "Flashlight on." else "Flashlight off."
                }
                "get_battery" -> {
                    val bm = act.getSystemService(Activity.BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

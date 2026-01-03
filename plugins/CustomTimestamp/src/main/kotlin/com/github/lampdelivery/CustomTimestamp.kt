package com.github.lampdelivery

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.after
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.aliucord.Logger
import android.view.View
import android.widget.TextView
import com.aliucord.entities.Plugin
import java.text.SimpleDateFormat
import java.util.*
import com.github.lampdelivery.CustomTimestampSettings

@AliucordPlugin
class CustomTimestamp : Plugin() {
    override fun start(context: Context) {
        settingsTab = Plugin.SettingsTab(CustomTimestampSettings::class.java).withArgs(settings)
        patcher.after<WidgetChatListAdapterItemMessage>("onConfigure", Int::class.java, com.discord.widgets.chat.list.entries.ChatListEntry::class.java) { hook ->
            val itemTimestampField = hook.thisObject.javaClass.getDeclaredField("itemTimestamp")
            itemTimestampField.isAccessible = true
            val timestampView = itemTimestampField.get(hook.thisObject) as? android.widget.TextView ?: return@after
            var text = timestampView.text.toString()

            val todayPrefix = settings.getString("todayPrefix", "Today at ")
            val todaySuffix = settings.getString("todaySuffix", "")
            val yesterdayPrefix = settings.getString("yesterdayPrefix", "Yesterday at ")
            val yesterdaySuffix = settings.getString("yesterdaySuffix", "")

            if (settings.getBool("hideToday", false)) {
                if (todayPrefix.isNotEmpty()) text = text.replace(todayPrefix, "")
                if (todaySuffix.isNotEmpty()) text = text.replace(todaySuffix, "")
            }

            if (settings.getBool("hideYesterday", false)) {
                if (yesterdayPrefix.isNotEmpty()) text = text.replace(yesterdayPrefix, "")
                if (yesterdaySuffix.isNotEmpty()) text = text.replace(yesterdaySuffix, "")
            }

            val customFormat = settings.getString("customDateFormat", "MMM dd, yyyy")
            val use24Hour = settings.getBool("use24Hour", false)
            val timeFormat = if (use24Hour) "HH:mm" else "hh:mm a"
            val inputFormats = listOf(
                "MMM dd, yyyy HH:mm", "dd MMM yyyy HH:mm", "MM/dd/yyyy HH:mm", "dd-MM-yyyy HH:mm", "yyyy/MM/dd HH:mm",
                "yyyy-MM-dd HH:mm",
                "MMM dd, yyyy hh:mm a", "dd MMM yyyy hh:mm a", "MM/dd/yyyy hh:mm a", "dd-MM-yyyy hh:mm a", "yyyy/MM/dd hh:mm a",
                "yyyy-MM-dd hh:mm a"
            )
            var reformatted = false

            for (fmt in inputFormats) {
                try {
                    val parsed = SimpleDateFormat(fmt, Locale.getDefault()).parse(text)
                    if (parsed != null) {
                        val datePart = SimpleDateFormat(customFormat, Locale.getDefault()).format(parsed)
                        val timePart = SimpleDateFormat(timeFormat, Locale.getDefault()).format(parsed)
                        text = "$datePart $timePart"
                        reformatted = true
                        break
                    }
                } catch (_: Throwable) {}
            }

            if (!reformatted) {
                val regex12 = Regex("""(?i)(\d{1,2}):(\d{2}) ?([AP]M)""")
                val regex24 = Regex("""(\d{2}):(\d{2})""")
                if (use24Hour) {
                    text = regex12.replace(text) { match ->
                        val hour = match.groupValues[1].toInt()
                        val minute = match.groupValues[2]
                        val ampm = match.groupValues[3].uppercase()
                        var hour24 = hour
                        if (ampm == "PM" && hour != 12) hour24 += 12
                        if (ampm == "AM" && hour == 12) hour24 = 0
                        String.format("%02d:%s", hour24, minute)
                    }
                } else {
                    text = regex24.replace(text) { match ->
                        val hour = match.groupValues[1].toInt()
                        val minute = match.groupValues[2]
                        val ampm = if (hour < 12) "AM" else "PM"
                        val hour12 = when {
                            hour == 0 -> 12
                            hour > 12 -> hour - 12
                            else -> hour
                        }
                        String.format("%d:%s %s", hour12, minute, ampm)
                    }
                }
            }

            timestampView.text = text
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}


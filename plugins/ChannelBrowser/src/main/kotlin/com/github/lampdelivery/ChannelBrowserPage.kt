package com.github.lampdelivery

import android.annotation.SuppressLint
import com.aliucord.Http
import android.view.Gravity
import android.view.View
import android.content.Context
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Constants
import com.aliucord.api.SettingsAPI
import com.lytefast.flexinput.R
import com.aliucord.fragments.SettingsPage
import com.discord.stores.StoreStream

class ChannelBrowserPage(val settings: SettingsAPI) : SettingsPage() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastView: View? = null
    private fun getCurrentGuildSettings(guildId: Long): Map<String, Any>? {
        return try {
            val store = StoreStream.getUserGuildSettings()
            val settingsMap = store.guildSettings
            val settings = settingsMap[guildId] ?: return null
            val field = settings.javaClass.declaredFields.find { it.name == "channelOverrides" }
            field?.isAccessible = true
            val overridesList = field?.get(settings) as? List<*> ?: return null
            val overridesMap = mutableMapOf<String, Int>()
            for (override in overridesList) {
                if (override == null) continue
                val chIdField = override.javaClass.declaredFields.find { it.name == "channelId" }
                val flagsField = override.javaClass.declaredFields.find { it.name == "flags" }
                chIdField?.isAccessible = true
                flagsField?.isAccessible = true
                val chId = chIdField?.get(override)?.toString()
                val flags = (flagsField?.get(override) as? Int) ?: 0
                if (chId != null) overridesMap[chId] = flags
            }
            mapOf("channel_overrides" to overridesMap)
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onViewBound(view: View) {
        lastView = view
        super.onViewBound(view)

        setActionBarTitle("Browse Channels")
        setActionBarSubtitle(null)

        val ctx = context ?: return
        val guildId = StoreStream.getGuildSelected().selectedGuildId
        val allChannelsRaw = StoreStream.getChannels().getChannelsForGuild(guildId)
        val hiddenChannels = settings.getObject("hiddenChannels", mutableListOf<String>()) as MutableList<String>

        val guildSettings = getCurrentGuildSettings(guildId)
        val channelOverrides = guildSettings?.get("channel_overrides")
        val channelOverridesMap = if (channelOverrides is Map<*, *>) {
            channelOverrides.entries.filter { it.key is String && it.value is Int }
                .associate { it.key as String to it.value as Int }
        } else emptyMap()

        try {
            val store = StoreStream.getUserGuildSettings()
            com.discord.utilities.rx.ObservableExtensionsKt.appSubscribe(
                store.observeGuildSettings(guildId),
                ChannelBrowserPage::class.java,
                ctx,
                {},
                { _: com.discord.utilities.error.Error -> },
                {},
                {},
                { _: Any? -> }
            )
        } catch (_: Throwable) {
        }
        val typeField =
            com.discord.api.channel.Channel::class.java.getDeclaredField("type").apply { isAccessible = true }
        val parentIdField =
            com.discord.api.channel.Channel::class.java.getDeclaredField("parentId").apply { isAccessible = true }
        val idField = com.discord.api.channel.Channel::class.java.getDeclaredField("id").apply { isAccessible = true }
        val nameField =
            com.discord.api.channel.Channel::class.java.getDeclaredField("name").apply { isAccessible = true }
        val linearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        addView(linearLayout)

        val categories = allChannelsRaw.values.filter {
            try {
                typeField.getInt(it) == 4
            } catch (_: Throwable) {
                false
            }
        }
        val channelsByCategory = mutableMapOf<Long, MutableList<com.discord.api.channel.Channel>>()
        val uncategorized = mutableListOf<com.discord.api.channel.Channel>()

        for (ch in allChannelsRaw.values) {
            val type = try {
                typeField.getInt(ch)
            } catch (_: Throwable) {
                -1
            }
            if (type == 4) continue
            val parentId = try {
                parentIdField.get(ch) as? Long
            } catch (_: Throwable) {
                null
            }
            if (parentId != null && allChannelsRaw.containsKey(parentId)) {
                channelsByCategory.getOrPut(parentId) { mutableListOf() }.add(ch)
            } else {
                uncategorized.add(ch)
            }
        }

        for (cat in categories) {
            val catName = try {
                nameField.get(cat) as? String ?: "Unnamed Category"
            } catch (_: Throwable) {
                "Unnamed Category"
            }
            val catId = try {
                idField.get(cat) as? Long
            } catch (_: Throwable) {
                null
            }
            val catRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 24, 0, 8)
                gravity = Gravity.CENTER_VERTICAL
            }
            val catTv = TextView(ctx, null, 0, R.i.UiKit_Settings_Item).apply {
                text = catName
                typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_bold)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val followLabel = TextView(ctx, null, 0, R.i.UiKit_Settings_Item).apply {
                text = "Follow Category"
                val color = try {
                    com.discord.utilities.color.ColorCompat.getThemedColor(ctx, R.b.colorInteractiveNormal)
                } catch (_: Throwable) {
                    0xFF222222.toInt()
                }
                setTextColor(color)
                typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_medium)
                textSize = 14f
                setPadding(16, 0, 16, 0)
            }
            val children = if (catId != null) channelsByCategory[catId] else null
            val childIds = children?.mapNotNull { ch ->
                try {
                    ch.javaClass.getDeclaredField("id").apply { isAccessible = true }.get(ch)?.toString()
                } catch (_: Throwable) {
                    null
                }
            } ?: emptyList()
            val isCategoryHiddenLocally = catId != null && hiddenChannels.contains(catId.toString())
            val checkedCount = childIds.count { id ->
                val flags = channelOverridesMap[id] ?: 4096
                (flags and 4096) != 0
            }
            val allChecked = checkedCount == childIds.size && childIds.isNotEmpty()
            val catToggle = Switch(ctx)
            catToggle.isChecked = !isCategoryHiddenLocally && allChecked
            catToggle.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            catToggle.setOnCheckedChangeListener { _, checked ->
                if (catId != null) {
                    catToggle.isEnabled = false
                    catToggle.isChecked = checked
                    Thread {
                        val newOverridesMap = mutableMapOf<String, MutableMap<String, Any>>()
                        newOverridesMap[catId.toString()] =
                            mutableMapOf("channel_id" to catId.toString(), "flags" to if (checked) 4096 else 0)
                        val localHidden =
                            settings.getObject("hiddenChannels", mutableListOf<String>()) as MutableList<String>
                        val prevHiddenKey = "catPrevHidden_$catId"
                        if (checked) {
                            val prevHidden = childIds.filter { localHidden.contains(it) }
                            settings.setObject(prevHiddenKey, prevHidden)
                            for (chId in childIds) {
                                localHidden.remove(chId)
                            }
                            localHidden.remove(catId.toString())
                        } else {
                            val prevHidden =
                                settings.getObject(prevHiddenKey, mutableListOf<String>()) as MutableList<String>
                            for (chId in childIds) {
                                if (prevHidden.contains(chId)) {
                                    if (!localHidden.contains(chId)) localHidden.add(chId)
                                } else {
                                    localHidden.remove(chId)
                                }
                            }
                            if (!localHidden.contains(catId.toString())) localHidden.add(catId.toString())
                        }
                        settings.setObject("hiddenChannels", localHidden)
                        val syncToPC = settings.getBool("syncToPC", true)
                        if (syncToPC) {
                            val patchBody = mapOf(
                                "guilds" to mapOf(
                                    guildId.toString() to mapOf(
                                        "channel_overrides" to newOverridesMap
                                    )
                                )
                            )
                            try {
                                val req = Http.Request.newDiscordRNRequest(
                                    "/users/@me/guilds/settings",
                                    "PATCH"
                                )
                                req.executeWithJson(patchBody)
                            } catch (_: Exception) {
                            }
                        }
                        lastView?.let { v ->
                            if (syncToPC) {
                                handler.post {
                                    catToggle.isEnabled = true
                                    onViewBound(v)
                                }
                            } else {
                                handler.postDelayed({
                                    catToggle.isEnabled = true
                                    onViewBound(v)
                                }, 250)
                            }
                        }
                    }.start()
                }
            }
            catRow.addView(catTv)
            catRow.addView(followLabel)
            catRow.addView(catToggle)
            linearLayout.addView(catRow)

            if (children != null) {
                val isCategoryFollowed = catToggle.isChecked
                for (ch in children) {
                    addChannelRowReflect(
                        ch, guildId, ctx, nameField,
                        channelOverridesMap, linearLayout, false, isCategoryFollowed, hiddenChannels
                    )
                }
            }
        }

        if (uncategorized.isNotEmpty()) {
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item).apply {
                text = "Uncategorized"
                typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_bold)
                textSize = 15f
                setPadding(0, 24, 0, 8)
            }.let { linearLayout.addView(it) }
            for (ch in uncategorized) {
                addChannelRowReflect(
                    ch,
                    guildId,
                    ctx,
                    nameField,
                    channelOverridesMap,
                    linearLayout,
                    grayOut = false,
                    parentCategoryHidden = false,
                    hiddenChannels = hiddenChannels
                )
            }
        }
    }

    private fun addChannelRowReflect(
        ch: com.discord.api.channel.Channel,
        guildId: Long,
        ctx: Context,
        nameField: java.lang.reflect.Field,
        channelOverridesMap: Map<String, Int>,
        linearLayout: LinearLayout,
        grayOut: Boolean = false,
        parentCategoryHidden: Boolean = false,
        hiddenChannels: MutableList<String>
    ) {
        val chName = try {
            nameField.get(ch) as? String ?: "Unnamed Channel"
        } catch (_: Throwable) {
            "Unnamed Channel"
        }
        val chId = try {
            ch.javaClass.getDeclaredField("id").apply { isAccessible = true }.get(ch)?.toString()
        } catch (_: Throwable) {
            null
        }
        val flags = if (chId != null) channelOverridesMap[chId] ?: 4096 else 4096
        val isHiddenLocally = hiddenChannels.contains(chId)
        val isCheckedRemote = (flags and 4096) != 0
        val isChecked = !isHiddenLocally && isCheckedRemote
        val suppressChannelListener = BooleanArray(1) { false }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconView = ImageView(ctx).apply {
            try {
                setImageDrawable(ctx.getDrawable(R.e.ic_channel_text))
                val scale = ctx.resources.displayMetrics.density
                val size = (20 * scale).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(0, 0, (8 * scale).toInt(), 0)
                    gravity = Gravity.CENTER_VERTICAL
                }
            } catch (_: Throwable) {
            }
        }
        row.addView(iconView)
        val tv = TextView(ctx, null, 0, R.i.UiKit_Settings_Item).apply {
            text = chName
            typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_medium)
            textSize = 14f
            alpha = if (grayOut) 0.5f else 1f
        }
        row.addView(tv)
        val cb = CheckBox(ctx)
        cb.isChecked = isChecked
        cb.setOnCheckedChangeListener { _, isNowChecked ->
            if (suppressChannelListener[0]) return@setOnCheckedChangeListener
            cb.isEnabled = false
            cb.isChecked = isNowChecked
            Thread {
                val localHidden = settings.getObject("hiddenChannels", mutableListOf<String>()) as MutableList<String>
                if (isNowChecked) {
                    localHidden.remove(chId)
                } else {
                    if (chId != null && !localHidden.contains(chId)) localHidden.add(chId)
                }
                settings.setObject("hiddenChannels", localHidden)
                val syncToPC = settings.getBool("syncToPC", true)
                if (syncToPC && chId != null) {
                    val patchBody = mapOf(
                        "channel_overrides" to mapOf(
                            chId to mapOf(
                                "muted" to false,
                                "mute_config" to null,
                                "message_notifications" to 1,
                                "flags" to if (isNowChecked) 0 else 4096
                            )
                        )
                    )
                    try {
                        val req = Http.Request.newDiscordRNRequest(
                            "/users/@me/guilds/$guildId/settings",
                            "PATCH"
                        )
                        req.executeWithJson(patchBody)
                    } catch (_: Exception) {
                    }
                }
                if (lastView != null) {
                    if (syncToPC) {
                        handler.post {
                            cb.isEnabled = true
                            onViewBound(lastView!!)
                        }
                    } else {
                        handler.postDelayed({
                            cb.isEnabled = true
                            onViewBound(lastView!!)
                        }, 250)
                    }
                }
            }.start()
        }
        if (parentCategoryHidden) {
            cb.isEnabled = false
            suppressChannelListener[0] = true
            cb.isChecked = false
            suppressChannelListener[0] = false
        }
        row.addView(cb)
        linearLayout.addView(row)
    }
}

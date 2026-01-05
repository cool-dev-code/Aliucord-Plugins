package com.github.lampdelivery

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.content.Context
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.ContextCompat
import com.aliucord.Constants
import com.aliucord.api.SettingsAPI
import com.lytefast.flexinput.R
import com.aliucord.fragments.SettingsPage
import com.discord.stores.StoreStream

import com.github.lampdelivery.ChannelBrowserProtoSettingsManager

class ChannelBrowserProtoPage(val settings: SettingsAPI, val channels: MutableList<String>, val protoSettingsManager: ChannelBrowserProtoSettingsManager) : SettingsPage() {
        fun addChannelRowReflect(
            ch: com.discord.api.channel.Channel,
            guildId: Long,
            ctx: Context,
            nameField: java.lang.reflect.Field,
            channelOverridesArr: MutableList<MutableMap<String, Any>>,
            linearLayout: LinearLayout,
            allChannelsRaw: Map<Long, com.discord.api.channel.Channel>,
            grayOut: Boolean = false,
            parentCategoryFollowed: Boolean = false,
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
            val override = channelOverridesArr.find { it["channel_id"].toString() == (chId ?: "") }
            val isHiddenLocally = hiddenChannels.contains(chId)
            val isHiddenInDiscord = if (override == null) {
                false
            } else {
                val flags = (override["flags"] as? Number)?.toInt() ?: 0
                (flags and 4096) == 0
            }
            val isChecked = !isHiddenLocally && !isHiddenInDiscord
            var suppressChannelListener = BooleanArray(1) { false }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 8, 16, 8)
                gravity = Gravity.CENTER_VERTICAL
            }
            val tv = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = chName
                typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_medium)
                textSize = 14f
                val color = try {
                    if (isChecked) {
                        com.discord.utilities.color.ColorCompat.getThemedColor(ctx, com.lytefast.flexinput.R.b.colorInteractiveMuted)
                    } else {
                        com.discord.utilities.color.ColorCompat.getThemedColor(ctx, com.lytefast.flexinput.R.b.colorInteractiveNormal)
                    }
                } catch (_: Throwable) {
                    if (isChecked) 0xFF222222.toInt() else 0xFF888888.toInt()
                }
                setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                try {
                    setCompoundDrawablesWithIntrinsicBounds(
                        ctx.getDrawable(R.e.ic_channel_text),
                        null, null, null
                    )
                    val scale = ctx.resources.displayMetrics.density
                    compoundDrawablePadding = (8 * scale).toInt()
                } catch (_: Throwable) {}
            }
            val cb = CheckBox(ctx)
            cb.isChecked = if (parentCategoryFollowed) true else isChecked
            cb.isEnabled = !parentCategoryFollowed
            cb.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            cb.setOnCheckedChangeListener { buttonView, checked ->
                if (suppressChannelListener[0]) return@setOnCheckedChangeListener
                val previousState = !checked
                val doAction = {
                    if (chId != null) {
                        suppressChannelListener[0] = true
                        Thread {
                            val channelOverrides = mutableMapOf<String, MutableMap<String, Any>>()
                            val toggledOverride = mutableMapOf<String, Any>("channel_id" to chId!!, "flags" to if (checked) 4096 else 0)
                            channelOverrides[chId!!] = toggledOverride
                            if (!checked) {
                                if (!hiddenChannels.contains(chId)) hiddenChannels.add(chId!!)
                            } else {
                                hiddenChannels.remove(chId)
                            }
                            settings.setObject("hiddenChannels", hiddenChannels)
                            protoSettingsManager.updateSettings { old ->
                                val guildIdStr = guildId.toString()
                                val builder = old.toBuilder()
                                var guildIdx = -1
                                for (i in 0 until builder.guildsCount) {
                                    if (builder.getGuilds(i).guildId == guildIdStr) {
                                        guildIdx = i
                                        break
                                    }
                                }
                                val guildBuilder = if (guildIdx != -1) {
                                    builder.getGuilds(guildIdx).toBuilder()
                                } else {
                                    channelbrowser.Settings.GuildSettings.newBuilder().setGuildId(guildIdStr)
                                }
                                val overrides = hiddenChannels.map { chId ->
                                    channelbrowser.Settings.ChannelOverride.newBuilder()
                                        .setChannelId(chId)
                                        .setFlags(0)
                                        .build()
                                }
                                guildBuilder.clearChannelOverrides().addAllChannelOverrides(overrides)
                                if (guildIdx != -1) {
                                    builder.setGuilds(guildIdx, guildBuilder.build())
                                } else {
                                    builder.addGuilds(guildBuilder.build())
                                }
                                builder.build()
                            }
                            val patchBody = mapOf(
                                "guilds" to mapOf(
                                    guildId.toString() to mapOf(
                                        "channel_overrides" to channelOverrides
                                    )
                                )
                            )
                            try {
                                logger.debug("PATCH body: $patchBody")
                                val req = com.aliucord.Http.Request.newDiscordRNRequest(
                                    "/users/@me/guilds/settings",
                                    "PATCH"
                                )
                                val resp = req.executeWithJson(patchBody)
                                logger.debug("PATCH response: ${resp.text()}")
                                handler.post {
                                    cb.isChecked = checked
                                    row.alpha = if (!checked || parentCategoryFollowed) 0.5f else 1.0f
                                    suppressChannelListener[0] = false
                                }
                            } catch (e: Exception) {
                                logger.error("PATCH error", e)
                                handler.post { suppressChannelListener[0] = false }
                            }
                        }.start()
                    }
                }
                if (settings.getBool("confirmActions", false)) {
                    val textColor = ContextCompat.getColor(ctx, R.c.primary_dark)
                    val customTitle = TextView(ctx).apply {
                        text = if (!checked) "Hide Channel" else "Restore Channel"
                        setTextColor(textColor)
                        typeface = ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_bold)
                        textSize = 20f
                        setPadding(32, 32, 32, 16)
                    }
                    val themedDialog = AlertDialog.Builder(ctx)
                        .setCustomTitle(customTitle)
                        .setMessage("Are you sure you want to ${if (!checked) "hide" else "restore"} this channel?")
                        .setPositiveButton("Yes") { _: android.content.DialogInterface, _: Int -> doAction() }
                        .setNegativeButton("No") { _: android.content.DialogInterface, _ ->
                            suppressChannelListener[0] = true
                            buttonView.isChecked = previousState
                            suppressChannelListener[0] = false
                        }
                        .setOnCancelListener {
                            buttonView.isChecked = previousState
                        }
                        .create()
                    themedDialog.show()
                    themeAlertDialogText(themedDialog, ctx)
                } else {
                    doAction()
                }
            }
            row.addView(tv)
            row.addView(cb)
            row.alpha = if (!isChecked || grayOut) 0.5f else 1.0f
            linearLayout.addView(row)
        }
    private val logger = com.aliucord.Logger("ChannelBrowserProto")
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastView: View? = null
    private fun deepCopyOverrides(orig: Any?): MutableList<MutableMap<String, Any>> {
        val result = mutableListOf<MutableMap<String, Any>>()
        if (orig is List<*>) {
            for (item in orig) {
                if (item is Map<*, *>) {
                    val map = HashMap<String, Any>()
                    for ((k, v) in item) {
                        if (k is String && v != null) {
                            map[k] = v
                        }
                    }
                    result.add(map)
                }
            }
        }
        return result
    }
    private fun themeAlertDialogText(dialog: AlertDialog, ctx: Context) {
        try {
            val textColorRes = R.c.primary_dark
            val textColor = ContextCompat.getColor(ctx, textColorRes)
            dialog.window?.decorView?.post {
                val messageId = android.R.id.message
                val messageView = dialog.findViewById<TextView>(messageId)
                messageView?.setTextColor(textColor)
                messageView?.setTypeface(ResourcesCompat.getFont(ctx, Constants.Fonts.whitney_medium))
            }
        } catch (_: Throwable) {}
    }

    @SuppressLint("SetTextI18n")
    override fun onViewBound(view: View) {
        lastView = view
        super.onViewBound(view)

        setActionBarTitle("Browse Channels")

        val ctx = context ?: return
        val guildId = StoreStream.getGuildSelected().selectedGuildId
        val allChannelsRaw = StoreStream.getChannels().getChannelsForGuild(guildId)
        val hiddenChannels = settings.getObject("hiddenChannels", mutableListOf<String>()) as MutableList<String>

        val loadingView = ProgressBar(ctx).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        addView(loadingView)

        fun buildUI(protoSettingsLoaded: Boolean) {
            removeView(loadingView)
            val allChannels = allChannelsRaw
            val channelsByCategory = mutableMapOf<Long, MutableList<com.discord.api.channel.Channel>>()
            val uncategorized = mutableListOf<com.discord.api.channel.Channel>()

            val protoSettings = try { protoSettingsManager.currentSettings } catch (_: Throwable) { null }
            val protoGuild = protoSettings?.guildsList?.find { it.guildId == guildId.toString() }
            val protoOverrides = protoGuild?.channelOverridesList?.associateBy { it.channelId } ?: emptyMap()

            val effectiveOverrides = protoOverrides

            val typeField = com.discord.api.channel.Channel::class.java.getDeclaredField("type").apply { isAccessible = true }
            val parentIdField = com.discord.api.channel.Channel::class.java.getDeclaredField("parentId").apply { isAccessible = true }
            val idField = com.discord.api.channel.Channel::class.java.getDeclaredField("id").apply { isAccessible = true }
            val nameField = com.discord.api.channel.Channel::class.java.getDeclaredField("name").apply { isAccessible = true }
            val linearLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            addView(linearLayout)

            for (ch in allChannels.values) {
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
                if (parentId != null && allChannels.containsKey(parentId)) {
                    channelsByCategory.getOrPut(parentId) { mutableListOf() }.add(ch)
                } else {
                    uncategorized.add(ch)
                }
            }

            val categories = allChannels.values.filter {
                try {
                    typeField.getInt(it) == 4
                } catch (_: Throwable) {
                    false
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
                        com.discord.utilities.color.ColorCompat.getThemedColor(ctx, com.lytefast.flexinput.R.b.colorInteractiveNormal)
                    } catch (_: Throwable) { 0xFF222222.toInt() }
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
                    val override = effectiveOverrides[id]
                    if (override == null) {
                        true
                    } else {
                        val flags = try {
                            val flagsField = override.javaClass.getDeclaredField("flags").apply { isAccessible = true }
                            (flagsField.get(override) as? Number)?.toInt() ?: 0
                        } catch (_: Throwable) { 0 }
                        (flags and 4096) != 0
                    }
                }
                val allChecked = checkedCount == childIds.size && childIds.isNotEmpty()
                val noneChecked = checkedCount == 0
                val catToggle = Switch(ctx)
                catToggle.isChecked = !isCategoryHiddenLocally && allChecked
                catToggle.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                catToggle.setOnCheckedChangeListener { _, checked ->
                    if (catId != null) {
                        catToggle.isEnabled = false
                        Thread {
                            val newOverridesMap = mutableMapOf<String, MutableMap<String, Any>>()
                            newOverridesMap[catId.toString()] = mutableMapOf("channel_id" to catId.toString(), "flags" to if (checked) 4096 else 0)
                            val localHidden = settings.getObject("hiddenChannels", mutableListOf<String>()) as MutableList<String>
                            val prevHiddenKey = "catPrevHidden_${catId.toString()}"
                            if (checked) {
                                val prevHidden = childIds.filter { localHidden.contains(it) }
                                settings.setObject(prevHiddenKey, prevHidden)
                                for (chId in childIds) {
                                    localHidden.remove(chId)
                                }
                                localHidden.remove(catId.toString())
                            } else {
                                val prevHidden = settings.getObject(prevHiddenKey, mutableListOf<String>()) as MutableList<String>
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
                            protoSettingsManager.updateSettings { old ->
                                val guildIdStr = guildId.toString()
                                val builder = old.toBuilder()
                                var guildIdx = -1
                                for (i in 0 until builder.guildsCount) {
                                    if (builder.getGuilds(i).guildId == guildIdStr) {
                                        guildIdx = i
                                        break
                                    }
                                }
                                val guildBuilder = if (guildIdx != -1) {
                                    builder.getGuilds(guildIdx).toBuilder()
                                } else {
                                    channelbrowser.Settings.GuildSettings.newBuilder().setGuildId(guildIdStr)
                                }
                                val overrides = localHidden.map { chId ->
                                    channelbrowser.Settings.ChannelOverride.newBuilder()
                                        .setChannelId(chId)
                                        .setFlags(0)
                                        .build()
                                }
                                guildBuilder.clearChannelOverrides().addAllChannelOverrides(overrides)
                                if (guildIdx != -1) {
                                    builder.setGuilds(guildIdx, guildBuilder.build())
                                } else {
                                    builder.addGuilds(guildBuilder.build())
                                }
                                builder.build()
                            }
                            val patchBody = mapOf(
                                "guilds" to mapOf(
                                    guildId.toString() to mapOf(
                                        "channel_overrides" to newOverridesMap
                                    )
                                )
                            )
                            try {
                                logger.debug("PATCH (category) body: $patchBody")
                                val req = com.aliucord.Http.Request.newDiscordRNRequest(
                                    "/users/@me/guilds/settings",
                                    "PATCH"
                                )
                                val resp = req.executeWithJson(patchBody)
                                logger.debug("PATCH (category) response: ${resp.text()}")
                            } catch (e: Exception) {
                                logger.error("PATCH (category) error", e)
                            }
                            handler.post {
                                catToggle.isEnabled = true
                            }
                        }.start()
                    }
                }
                catRow.addView(catTv)
                catRow.addView(followLabel)
                catRow.addView(catToggle)
                linearLayout.addView(catRow)

                val childCheckboxes = mutableListOf<CheckBox>()
                val childCheckedStates = mutableMapOf<CheckBox, Boolean>()
                val checkedStateKey = "catChildChecked_${catId}"
                val prevCheckedMap = try {
                    val obj = settings.getObject(checkedStateKey, null) as java.util.Map<*, *>
                    val map = mutableMapOf<String, Boolean>()
                    for (entry in obj.entrySet()) {
                        val k = entry.key
                        val v = entry.value
                        if (k is String && v is Boolean) {
                            map[k] = v
                        }
                    }
                    map
                } catch (_: Throwable) { emptyMap() }
                if (children != null) {
                    val isCategoryFollowed = catToggle.isChecked
                    for (ch in children) {
                        var childCb: CheckBox? = null
                        fun addRowWithCb(): Unit = addChannelRowReflect(ch, guildId, ctx, nameField, mutableListOf(), linearLayout, allChannelsRaw, false, isCategoryFollowed, effectiveOverrides.keys.toMutableList()).also {
                            val row = linearLayout.getChildAt(linearLayout.childCount - 1) as? LinearLayout
                            childCb = row?.findViewById<CheckBox>(row.childCount - 1)
                        }
                        addRowWithCb()
                        childCb?.let {
                            childCheckboxes.add(it)
                            val chId = try { ch.javaClass.getDeclaredField("id").apply { isAccessible = true }.get(ch)?.toString() } catch (_: Throwable) { null }
                            if (chId != null) it.tag = chId
                            if (chId != null && prevCheckedMap.containsKey(chId)) {
                                it.isChecked = prevCheckedMap[chId] == true
                            }
                            childCheckedStates[it] = it.isChecked 
                        }
                    }
                }
                catToggle.setOnCheckedChangeListener { _, checked ->
                    if (catId != null) {
                        catToggle.isEnabled = false
                        Thread {
                            val newOverridesMap = mutableMapOf<String, MutableMap<String, Any>>()
                            newOverridesMap[catId.toString()] = mutableMapOf("channel_id" to catId.toString(), "flags" to if (checked) 4096 else 0)
                            val localHidden = settings.getObject("hiddenChannels", mutableListOf<String>()) as MutableList<String>
                            val prevHiddenKey = "catPrevHidden_${catId.toString()}"
                            if (checked) {
                                val prevHidden = childIds.filter { localHidden.contains(it) }
                                settings.setObject(prevHiddenKey, prevHidden)
                                for (chId in childIds) {
                                    localHidden.remove(chId)
                                }
                                localHidden.remove(catId.toString())
                            } else {
                                val prevHidden = settings.getObject(prevHiddenKey, mutableListOf<String>()) as MutableList<String>
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
                            protoSettingsManager.updateSettings { old ->
                                val guildIdStr = guildId.toString()
                                val builder = old.toBuilder()
                                var guildIdx = -1
                                for (i in 0 until builder.guildsCount) {
                                    if (builder.getGuilds(i).guildId == guildIdStr) {
                                        guildIdx = i
                                        break
                                    }
                                }
                                val guildBuilder = if (guildIdx != -1) {
                                    builder.getGuilds(guildIdx).toBuilder()
                                } else {
                                    channelbrowser.Settings.GuildSettings.newBuilder().setGuildId(guildIdStr)
                                }
                                val overrides = localHidden.map { chId ->
                                    channelbrowser.Settings.ChannelOverride.newBuilder()
                                        .setChannelId(chId)
                                        .setFlags(0)
                                        .build()
                                }
                                guildBuilder.clearChannelOverrides().addAllChannelOverrides(overrides)
                                if (guildIdx != -1) {
                                    builder.setGuilds(guildIdx, guildBuilder.build())
                                } else {
                                    builder.addGuilds(guildBuilder.build())
                                }
                                builder.build()
                            }
                            val patchBody = mapOf(
                                "guilds" to mapOf(
                                    guildId.toString() to mapOf(
                                        "channel_overrides" to newOverridesMap
                                    )
                                )
                            )
                            try {
                                logger.debug("PATCH (category) body: $patchBody")
                                val req = com.aliucord.Http.Request.newDiscordRNRequest(
                                    "/users/@me/guilds/settings",
                                    "PATCH"
                                )
                                val resp = req.executeWithJson(patchBody)
                                logger.debug("PATCH (category) response: ${resp.text()}")
                            } catch (e: Exception) {
                                logger.error("PATCH (category) error", e)
                            }
                            handler.post {
                                catToggle.isEnabled = true
                                if (checked) {
                                    val checkedMap = mutableMapOf<String, Boolean>()
                                    for (cb in childCheckboxes) {
                                        childCheckedStates[cb] = cb.isChecked
                                        val tag = cb.tag as? String
                                        if (tag != null) checkedMap[tag] = cb.isChecked
                                        cb.isChecked = true
                                        cb.isEnabled = false
                                    }
                                    val chIds = children?.mapNotNull { ch ->
                                        try { ch.javaClass.getDeclaredField("id").apply { isAccessible = true }.get(ch)?.toString() } catch (_: Throwable) { null }
                                    } ?: emptyList()
                                    val checkedMapById = chIds.associateWith { id -> checkedMap[id] ?: true }
                                    settings.setObject(checkedStateKey, checkedMapById)
                                } else {
                                    val checkedMap = try {
                                        val obj = settings.getObject(checkedStateKey, null) as java.util.Map<*, *>
                                        val map = mutableMapOf<String, Boolean>()
                                        for (entry in obj.entrySet()) {
                                            val k = entry.key
                                            val v = entry.value
                                            if (k is String && v is Boolean) {
                                                map[k] = v
                                            }
                                        }
                                        map
                                    } catch (_: Throwable) { emptyMap() }
                                    val chIds = children?.mapNotNull { ch ->
                                        try { ch.javaClass.getDeclaredField("id").apply { isAccessible = true }.get(ch)?.toString() } catch (_: Throwable) { null }
                                    } ?: emptyList()
                                    for ((i, cb) in childCheckboxes.withIndex()) {
                                        val chId = chIds.getOrNull(i)
                                        cb.isChecked = if (chId != null && checkedMap.containsKey(chId)) checkedMap[chId] == true else true
                                        cb.isEnabled = true
                                    }
                                }
                            }
                        }.start()
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
                    addChannelRowReflect(ch, guildId, ctx, nameField, mutableListOf(), linearLayout, allChannelsRaw, false, false, effectiveOverrides.keys.toMutableList())
                }
            }
        }

        protoSettingsManager.observeSettings().firstOrError()
            .subscribe({ protoSettings ->
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    protoSettingsManager.updateSettings { old ->
                        val guildIdStr = guildId.toString()
                        val builder = old.toBuilder()
                        var guildIdx = -1
                        for (i in 0 until builder.guildsCount) {
                            if (builder.getGuilds(i).guildId == guildIdStr) {
                                guildIdx = i
                                break
                            }
                        }
                        val guildBuilder = if (guildIdx != -1) {
                            builder.getGuilds(guildIdx).toBuilder()
                        } else {
                            channelbrowser.Settings.GuildSettings.newBuilder().setGuildId(guildIdStr)
                        }
                        val overrides = hiddenChannels.map { chId ->
                            channelbrowser.Settings.ChannelOverride.newBuilder()
                                .setChannelId(chId)
                                .setFlags(0)
                                .build()
                        }
                        guildBuilder.clearChannelOverrides().addAllChannelOverrides(overrides)
                        if (guildIdx != -1) {
                            builder.setGuilds(guildIdx, guildBuilder.build())
                        } else {
                            builder.addGuilds(guildBuilder.build())
                        }
                        builder.build()
                    }
                    buildUI(true)
                } else {
                    handler.post {
                        protoSettingsManager.updateSettings { old ->
                            val guildIdStr = guildId.toString()
                            val builder = old.toBuilder()
                            var guildIdx = -1
                            for (i in 0 until builder.guildsCount) {
                                if (builder.getGuilds(i).guildId == guildIdStr) {
                                    guildIdx = i
                                    break
                                }
                            }
                            val guildBuilder = if (guildIdx != -1) {
                                builder.getGuilds(guildIdx).toBuilder()
                            } else {
                                channelbrowser.Settings.GuildSettings.newBuilder().setGuildId(guildIdStr)
                            }
                            val overrides = hiddenChannels.map { chId ->
                                channelbrowser.Settings.ChannelOverride.newBuilder()
                                    .setChannelId(chId)
                                    .setFlags(0)
                                    .build()
                            }
                            guildBuilder.clearChannelOverrides().addAllChannelOverrides(overrides)
                            if (guildIdx != -1) {
                                builder.setGuilds(guildIdx, guildBuilder.build())
                            } else {
                                builder.addGuilds(guildBuilder.build())
                            }
                            builder.build()
                        }
                        buildUI(true)
                    }
                }
            }, { e ->
                handler.post {
                    logger.error("Failed to load proto settings for page", e as Exception)
                    buildUI(false)
                }
            })
    }
}

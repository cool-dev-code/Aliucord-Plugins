package com.github.lampdelivery

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.SettingsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.widgets.LinearLayout
import com.discord.api.channel.Channel
import com.discord.app.AppBottomSheet
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.aliucord.wrappers.users.*
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.user.UserUtils
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.lytefast.flexinput.R
import java.util.*

@AliucordPlugin
class CustomNameFormat : Plugin() {
    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }

    class PluginSettings(private val settings: SettingsAPI) : AppBottomSheet() {
        override fun getContentViewResId(): Int = 0

        @SuppressLint("SetTextI18n")
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            val context = inflater.context
            val layout = LinearLayout(context)
            layout.setBackgroundColor(ColorCompat.getThemedColor(context, R.b.colorBackgroundPrimary))

            val radios = listOf(
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO, "Nickname (Username)", null),
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO, "Nickname (Tag)", null),
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO, "Display Name (Username)", null),
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO, "Display Name (Tag)", null),
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO, "Username", null),
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO, "Username (Nickname)", null),
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO, "Username (Display Name)", null)
            )

            val radioManager = RadioManager(radios)
            var format = Format.valueOf(settings.getString("format", Format.NICKNAME_USERNAME.name))

            for ((i, radio) in radios.withIndex()) {
                radio.e {
                    settings.setString("format", Format.values()[i].name)
                    radioManager.a(radio)
                }
                layout.addView(radio)
                if (i == format.ordinal) radioManager.a(radio)
            }

            return layout
        }
    }

    enum class Format {
        NICKNAME_USERNAME,
        NICKNAME_TAG,
        DISPLAYNAME_USERNAME,
        DISPLAYNAME_TAG,
        USERNAME,
        USERNAME_NICKNAME,
        USERNAME_DISPLAYNAME
    }

    override fun start(context: Context) {
        patcher.patch(
            GuildMember::class.java.getDeclaredMethod("getNickOrUsername", User::class.java, GuildMember::class.java, Channel::class.java, List::class.java),
            Hook { param ->
                val user = param.args[0] as User
                val member = param.args[1] as? GuildMember
                val nickname = member?.nick
                val displayName = user.globalName ?: user.username
                val username = user.username
                val res = param.result as String
                if (res == username) return@Hook
                param.result = getFormatted(nickname, displayName, username, res, user)
            }
        )

        // fix custom format in embeds
        patcher.patch(
            WidgetChatListAdapterItemEmbed::class.java.getDeclaredMethod("getModel", Any::class.java, Any::class.java),
                Hook { param ->
                    val mapResult = param.result
                    if (mapResult !is MutableMap<*, *>) return@Hook
                    if (mapResult.isEmpty()) return@Hook
                    val users = StoreStream.getUsers().users
                    for ((idAny, valueAny) in mapResult) {
                        val id = idAny as? Long ?: continue
                        val value = valueAny as? String ?: continue
                        val user = users[id]
                        if (user != null) {
                            @Suppress("UNCHECKED_CAST")
                            (mapResult as MutableMap<Any?, Any?>)[id] = getFormatted(null, user.globalName ?: user.username, user.username, value, user)
                        }
                    }
            }
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private fun getFormatted(nickname: String?, displayName: String, username: String, res: String, user: User): String {
        val format = Format.valueOf(settings.getString("format", Format.NICKNAME_USERNAME.name))
        return when (format) {
            Format.NICKNAME_USERNAME -> "${nickname ?: displayName} ($username)"
            Format.NICKNAME_TAG -> "${nickname ?: displayName} ($username${UserUtils.INSTANCE.getDiscriminatorWithPadding(user)})"
            Format.DISPLAYNAME_USERNAME -> "$displayName ($username)"
            Format.DISPLAYNAME_TAG -> "$displayName ($username${UserUtils.INSTANCE.getDiscriminatorWithPadding(user)})"
            Format.USERNAME -> username
            Format.USERNAME_NICKNAME -> "$username (${nickname ?: displayName})"
            Format.USERNAME_DISPLAYNAME -> "$username ($displayName)"
        }
    }
}


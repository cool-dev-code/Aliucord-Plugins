package com.github.lampdelivery

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.models.member.GuildMember
import com.discord.models.user.User as ModelUser
import com.discord.api.channel.Channel
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.stores.StoreStream
import com.discord.utilities.user.UserUtils
import com.aliucord.wrappers.users.globalName

@AliucordPlugin
class CustomNameFormat : Plugin() {
    enum class Format {
        NICKNAME_USERNAME,
        NICKNAME_TAG,
        USERNAME,
        USERNAME_NICKNAME,
        DISPLAYNAME_USERNAME,
        DISPLAYNAME_TAG,
        USERNAME_DISPLAYNAME
    }

    override fun start(context: Context) {
        // Patch for getNickOrUsername removed to prevent build errors if GuildMember$Companion does not exist

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
                    val user = users[id] as? ModelUser ?: continue
                    val username = user.username
                    (mapResult as MutableMap<Any?, Any?>)[id] = getFormatted(username, value, user)
                }
            }
        )

        try {
            val userNameFormatterClass = Class.forName("com.discord.widgets.user.UserNameFormatterKt")
            val getSpannableMethod = userNameFormatterClass.getDeclaredMethod(
                "getSpannableForUserNameWithDiscrim",
                Class.forName("com.discord.models.user.User"),
                String::class.java,
                Context::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java
            )
            patcher.patch(getSpannableMethod, Hook { param ->
                val user = param.args[0] as? ModelUser ?: return@Hook
                val username = user.username
                val res = param.args[1] as? String ?: username
                param.args[1] = getFormatted(username, res, user)
            })
        } catch (_: Throwable) {}


        try {
            val userProfileHeaderViewClass = Class.forName("com.discord.widgets.user.profile.UserProfileHeaderView")
            val getSecondaryNameMethod = userProfileHeaderViewClass.getDeclaredMethod(
                "getSecondaryNameTextForUser",
                Class.forName("com.discord.models.user.User"),
                Class.forName("com.discord.models.member.GuildMember")
            )
            patcher.patch(getSecondaryNameMethod, Hook { param ->
                val user = param.args[0] as? ModelUser ?: return@Hook
                val username = user.username
                val res = param.result as? String ?: username
                param.result = getFormatted(username, res, user)
            })
        } catch (_: Throwable) {}

        try {
            val userProfileHeaderViewClass = Class.forName("com.discord.widgets.user.profile.UserProfileHeaderView")
            val configureSecondaryNameMethod = userProfileHeaderViewClass.declaredMethods.firstOrNull {
                it.name == "configureSecondaryName" && it.parameterTypes.size == 1
            }
            if (configureSecondaryNameMethod != null) {
                patcher.patch(configureSecondaryNameMethod, Hook { param ->
                    // No-op, prevents Discord from overriding the secondary name
                })
            }
        } catch (_: Throwable) {}
    }

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }

    class PluginSettings(private val settings: com.aliucord.api.SettingsAPI) : com.discord.app.AppBottomSheet() {
        override fun getContentViewResId(): Int = 0

        @SuppressLint("SetTextI18n")
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            val context = inflater.context
            val layout = com.aliucord.widgets.LinearLayout(context)
            layout.setBackgroundColor(com.discord.utilities.color.ColorCompat.getThemedColor(context, com.lytefast.flexinput.R.b.colorBackgroundPrimary))

            val radios = listOf(
                com.aliucord.Utils.createCheckedSetting(context, com.discord.views.CheckedSetting.ViewType.RADIO, "Nickname (Username)", null),
                com.aliucord.Utils.createCheckedSetting(context, com.discord.views.CheckedSetting.ViewType.RADIO, "Nickname (Tag)", null),
                com.aliucord.Utils.createCheckedSetting(context, com.discord.views.CheckedSetting.ViewType.RADIO, "Username", null),
                com.aliucord.Utils.createCheckedSetting(context, com.discord.views.CheckedSetting.ViewType.RADIO, "Username (Nickname)", null),
                com.aliucord.Utils.createCheckedSetting(context, com.discord.views.CheckedSetting.ViewType.RADIO, "Display Name (Username)", null),
                com.aliucord.Utils.createCheckedSetting(context, com.discord.views.CheckedSetting.ViewType.RADIO, "Display Name (Tag)", null),
                com.aliucord.Utils.createCheckedSetting(context, com.discord.views.CheckedSetting.ViewType.RADIO, "Username (Display Name)", null)
            )

            val radioManager = com.discord.views.RadioManager(radios)
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

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private fun getFormatted(username: String, res: String, user: ModelUser): String {
        val displayName = user.globalName ?: username
        return when (Format.valueOf(settings.getString("format", Format.NICKNAME_USERNAME.name))) {
            Format.NICKNAME_USERNAME -> "$res ($username)"
            Format.NICKNAME_TAG -> "$res ($username${UserUtils.INSTANCE.getDiscriminatorWithPadding(user)})"
            Format.USERNAME -> username
            Format.USERNAME_NICKNAME -> "$username ($res)"
            Format.DISPLAYNAME_USERNAME -> "$displayName ($username)"
            Format.DISPLAYNAME_TAG -> "$displayName ($username${UserUtils.INSTANCE.getDiscriminatorWithPadding(user)})"
            Format.USERNAME_DISPLAYNAME -> "$username ($displayName)"
        }
    }
}

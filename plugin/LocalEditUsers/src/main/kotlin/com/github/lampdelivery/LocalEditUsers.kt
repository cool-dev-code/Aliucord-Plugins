package com.github.lampdelivery

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.AppCompatTextView
import com.aliucord.*
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.fragments.SettingsPage
import com.aliucord.patcher.Hook
import com.aliucord.utils.GsonUtils
import com.aliucord.api.SettingsAPI
import com.google.gson.reflect.TypeToken
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

@AliucordPlugin(requiresRestart = false)
class LocalEditUsers : Plugin() {

    private val cache = ConcurrentHashMap<String, Drawable>()

    private fun getSettingsApi(): SettingsAPI {
        val field = Plugin::class.java.getDeclaredField("settings")
        field.isAccessible = true
        return field.get(this) as SettingsAPI
    }

    init {
        settingsTab = SettingsTab(
            LocalEditUsersSettings::class.java,
            SettingsTab.Type.BOTTOM_SHEET
        )
    }

    override fun start(context: Context) {
        patcher.patch(
            "com.discord.widgets.user.profile.UserProfileHeaderView",
            "setUser",
            arrayOf(Long::class.java),
            Hook { hook ->
                val userId = hook.args[0] as? Long ?: return@Hook
                val headerView = hook.thisObject as View
                val layoutId = Utils.getResId("user_profile_header", "id")
                val layout = headerView.findViewById<LinearLayout>(layoutId)
                if (layout != null && layout.findViewWithTag<View>("localeditusers_button") == null) {
                    val btn = TextView(headerView.context).apply {
                        id = View.generateViewId()
                        tag = "localeditusers_button"
                        text = "Edit User"
                        setBackgroundColor(Color.parseColor("#5865F2"))
                        setTextColor(Color.WHITE)
                        setPadding(20, 10, 20, 10)
                        setOnClickListener { showEditDialog(userId, headerView.context) }
                    }
                    layout.addView(btn)
                }
            }
        )

        patcher.patch(
            "com.discord.widgets.user.AvatarView",
            "setUser",
            arrayOf(Long::class.java, Boolean::class.java),
            Hook { hook ->
                val userId = hook.args[0] as? Long ?: return@Hook
                val entry = getOverrides(getSettingsApi())[userId] ?: return@Hook
                entry.avatarUrl?.let { avatarUrl ->
                    loadImage(avatarUrl) { drawable -> trySetAvatar(hook.thisObject, drawable) }
                }
            }
        )

        patcher.patch(
            "com.discord.widgets.user.profile.UserProfileHeaderView",
            "setUser",
            arrayOf(Long::class.java),
            Hook { hook ->
                val userId = hook.args[0] as? Long ?: return@Hook
                val entry = getOverrides(this.getSettingsApi())[userId] ?: return@Hook
                entry.bannerUrl?.let { bannerUrl ->
                    loadImage(bannerUrl) { drawable -> trySetBanner(hook.thisObject, drawable) }
                }
            }
        )

        patcher.patch(
            "com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage",
            "bind",
            emptyArray(),
            Hook { hook ->
                val userId = extractAuthorId(hook.args) ?: return@Hook
                val entry = getOverrides(this.getSettingsApi())[userId] ?: return@Hook
                entry.accentColor?.let { color ->
                    trySetAccentColor(hook.thisObject, color)
                }
            }
        )

        patcher.patch(
            "com.discord.widgets.user.profile.UserProfileActionsView",
            "onViewCreated",
            arrayOf(View::class.java, Bundle::class.java),
            Hook { hook ->
                val view = hook.args[0] as? View ?: return@Hook
                val ctx = view.context

                val actionsLayout = view.findViewById<ViewGroup>(Utils.getResId("user_profile_actions", "id"))
                    ?: return@Hook

                if (actionsLayout.findViewWithTag<View>("localeditusers_button") != null) return@Hook

                val fragmentField = hook.thisObject.javaClass.getDeclaredField("fragment")
                fragmentField.isAccessible = true
                val fragment = fragmentField.get(hook.thisObject) as? androidx.fragment.app.Fragment
                val userId = fragment?.arguments?.getLong("USER_ID") ?: return@Hook

                val btn = TextView(ctx).apply {
                    id = View.generateViewId()
                    tag = "localeditusers_button"
                    text = "Edit Local User"
                    setBackgroundColor(Color.parseColor("#5865F2"))
                    setTextColor(Color.WHITE)
                    setPadding(20, 10, 20, 10)
                    setOnClickListener { showEditDialog(userId, ctx) }
                }

                actionsLayout.addView(btn)
            }
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        cache.clear()
    }

    fun showEditDialog(userId: Long, ctx: Context) {
        val overrides = getOverrides(getSettingsApi())
        val entry = overrides[userId]

        val avatarInput = EditText(ctx).apply {
            hint = "Avatar URL"
            setText(entry?.avatarUrl ?: "")
        }
        val bannerInput = EditText(ctx).apply {
            hint = "Banner URL"
            setText(entry?.bannerUrl ?: "")
        }
        val colorInput = EditText(ctx).apply {
            hint = "Accent Color (#RRGGBB)"
            setText(entry?.accentColor?.let { "#%06X".format(0xFFFFFF and it) } ?: "")
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            addView(avatarInput)
            addView(bannerInput)
            addView(colorInput)
        }

        AlertDialog.Builder(ctx)
            .setTitle("Edit User $userId")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val avatarUrl = avatarInput.text.toString().takeIf { it.isNotBlank() }
                val bannerUrl = bannerInput.text.toString().takeIf { it.isNotBlank() }
                val accentColor = try { Color.parseColor(colorInput.text.toString()) } catch (_: Throwable) { null }
                if (avatarUrl != null || bannerUrl != null || accentColor != null) {
                    overrides[userId] = OverrideEntry(userId, avatarUrl, bannerUrl, accentColor)
                } else {
                    overrides.remove(userId)
                }
                saveOverrides(getSettingsApi(), overrides)
                Utils.showToast("Overrides saved for $userId")
            }
            .setNeutralButton("Reset") { _, _ ->
                overrides.remove(userId)
                saveOverrides(getSettingsApi(), overrides)
                Utils.showToast("Overrides reset for $userId")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadImage(url: String, cb: (Drawable) -> Unit) {
        cache[url]?.let { cb(it); return }
        Utils.threadPool.execute {
            try {
                URL(url).openStream().use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    val drawable = BitmapDrawable(Utils.appContext.resources, bmp)
                    cache[url] = drawable
                    Utils.mainThread.post { cb(drawable) }
                }
            } catch (_: Throwable) { }
        }
    }

    private fun trySetAvatar(target: Any, drawable: Drawable) {
        runCatching {
            val f = target.javaClass.getDeclaredField("imageView").apply { isAccessible = true }
            (f.get(target) as? ImageView)?.setImageDrawable(drawable)
        }
    }

    private fun trySetBanner(target: Any, drawable: Drawable) {
        runCatching {
            val f = target.javaClass.getDeclaredField("bannerView").apply { isAccessible = true }
            (f.get(target) as? ImageView)?.setImageDrawable(drawable)
        }
    }

    private fun trySetAccentColor(target: Any, color: Int) {
        runCatching {
            val f = target.javaClass.getDeclaredField("authorName").apply { isAccessible = true }
            (f.get(target) as? TextView)?.setTextColor(color)
        }
    }

    private fun extractAuthorId(args: Array<Any?>): Long? {
        args.forEach { a ->
            if (a is com.discord.models.message.Message) return a.author?.id
            if (a is com.discord.models.user.User) return a.id
        }
        return null
    }

    companion object {
        fun getOverrides(settings: SettingsAPI): MutableMap<Long, OverrideEntry> {
            val json = settings.getString("all_overrides", null) ?: return mutableMapOf()
            return runCatching {
                GsonUtils.fromJson<MutableMap<Long, OverrideEntry>>(
                    json, object : TypeToken<MutableMap<Long, OverrideEntry>>() {}.type
                )
            }.getOrDefault(mutableMapOf())
        }

        fun saveOverrides(settings: SettingsAPI, map: MutableMap<Long, OverrideEntry>) {
            settings.setString("all_overrides", GsonUtils.toJson(map))
        }
    }

    data class OverrideEntry(
        val userId: Long,
        val avatarUrl: String? = null,
        val bannerUrl: String? = null,
        val accentColor: Int? = null
    )
}

class LocalEditUsersSettings : SettingsPage() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val plugin = PluginManager.plugins["LocalEditUsers"] as? LocalEditUsers
            ?: return
        val overrides = LocalEditUsers.getOverrides(plugin.settings)

        linearLayout.addView(AppCompatTextView(requireContext()).apply {
            text = "Locally edited users:"
            textSize = 18f
            setPadding(0, 16, 0, 16)
        })

        if (overrides.isEmpty()) {
            linearLayout.addView(AppCompatTextView(requireContext()).apply {
                text = "No overrides set."
                setPadding(0, 8, 0, 8)
            })
        } else {
            for ((userId, entry) in overrides) {
                val desc = buildString {
                    append("User $userId")
                    if (!entry.avatarUrl.isNullOrBlank()) append("\nAvatar: ${entry.avatarUrl}")
                    if (!entry.bannerUrl.isNullOrBlank()) append("\nBanner: ${entry.bannerUrl}")
                    if (entry.accentColor != null) append("\nColor: #${"%06X".format(0xFFFFFF and entry.accentColor)}")
                }
                linearLayout.addView(AppCompatTextView(requireContext()).apply {
                    text = desc
                    setPadding(0, 8, 0, 8)
                    setOnClickListener {
                        plugin.showEditDialog(userId, requireContext())
                    }
                })
            }
            val clearBtn = Button(requireContext()).apply {
                text = "Clear All Overrides"
                setOnClickListener {
                    LocalEditUsers.saveOverrides(plugin.settings, mutableMapOf())
                    Utils.showToast("All overrides cleared")
                    requireActivity().recreate()
                }
            }
            linearLayout.addView(clearBtn)
        }
    }
}

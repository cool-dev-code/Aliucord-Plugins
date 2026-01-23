package com.aliucord.plugins.voicemessages;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.aliucord.patcher.Patcher;

import de.robv.android.xposed.XC_MethodHook;
import kotlin.Unit;

@AliucordPlugin
@SuppressWarnings("unused")
public final class VoiceMessages extends Plugin {

    private static final String FLEX_INPUT =
            "com.lytefast.flexinput.fragment.FlexInputFragment";

    @Override
    public void start(Context context) throws Throwable {

        Class<?> flexInput = Class.forName(FLEX_INPUT);

        Patcher.addHook(
                flexInput.getDeclaredMethod("onViewCreated", View.class),
                new Hook((XC_MethodHook.MethodHookParam param) -> {

                    View root = (View) param.args[0];
                    if (!(root instanceof ViewGroup)) return;

                    ViewGroup group = (ViewGroup) root;

                    // Prevent duplicate injection
                    if (group.findViewWithTag("aliucord_voice_button") != null)
                        return;

                    Context ctx = root.getContext();

                    ImageButton button = new ImageButton(ctx);
                    button.setTag("aliucord_voice_button");

                    button.setImageDrawable(
                            Utils.tintDrawable(
                                    ctx.getDrawable(
                                            ctx.getResources().getIdentifier(
                                                    "ic_mic_24dp",
                                                    "drawable",
                                                    ctx.getPackageName()
                                            )
                                    ),
                                    Utils.getThemeColor(ctx, "interactive-normal")
                            )
                    );

                    button.setBackground(null);
                    button.setOnClickListener(v ->
                            Utils.showToast("Voice Messages not implemented")
                    );

                    group.addView(button);
                })
        );
    }

    @Override
    public void stop(Context context) {
        // Hooks auto-unregister
    }
}

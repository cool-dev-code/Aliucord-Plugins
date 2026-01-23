package com.aliucord.plugins.voicemessages;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.core.content.ContextCompat;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.utils.DimenUtils;
import com.lytefast.flexinput.fragment.FlexInputFragment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@AliucordPlugin
public final class VoiceMessages extends Plugin {

    private static final String VIEW_TAG = "aliucord_voice_message_button";

    // Discord internals (reflected)
    private Object voiceManager;
    private Method startRecording;
    private Method stopRecording;

    @Override
    public void start(Context context) {

        // ---- FIX #1: reflection instead of direct import ----
        try {
            Class<?> cls =
                Class.forName("com.discord.utilities.voice.VoiceMessageManager");

            Field instance = cls.getDeclaredField("INSTANCE");
            voiceManager = instance.get(null);

            startRecording = cls.getDeclaredMethod("startRecording");
            stopRecording = cls.getDeclaredMethod("stopAndSendRecording");

        } catch (Throwable t) {
            logger.error("VoiceMessages: failed to init VoiceMessageManager", t);
            return;
        }

        patcher.after(
            FlexInputFragment.class,
            "onViewCreated",
            View.class,
            android.os.Bundle.class,
            hook -> {

                View root = (View) hook.args[0];
                ViewGroup container = root.findViewById(
                    context.getResources().getIdentifier(
                        "chat_input_container",
                        "id",
                        context.getPackageName()
                    )
                );

                if (container == null) return;

                // ---- FIX #2: remove existing injected view ----
                View old = container.findViewWithTag(VIEW_TAG);
                if (old != null) {
                    container.removeView(old);
                }

                ImageButton button = new ImageButton(context);
                button.setTag(VIEW_TAG);
                button.setImageDrawable(
                    ContextCompat.getDrawable(
                        context,
                        android.R.drawable.ic_btn_speak_now
                    )
                );

                ViewGroup.MarginLayoutParams lp =
                    new ViewGroup.MarginLayoutParams(
                        DimenUtils.dpToPx(36),
                        DimenUtils.dpToPx(36)
                    );
                lp.setMarginEnd(DimenUtils.dpToPx(6));
                button.setLayoutParams(lp);
                button.setBackground(null);

                button.setOnTouchListener((v, e) -> {
                    try {
                        if (e.getAction() == MotionEvent.ACTION_DOWN) {
                            startRecording.invoke(voiceManager);
                            return true;
                        }

                        if (e.getAction() == MotionEvent.ACTION_UP ||
                            e.getAction() == MotionEvent.ACTION_CANCEL) {
                            stopRecording.invoke(voiceManager);
                            return true;
                        }
                    } catch (Throwable t) {
                        logger.error("VoiceMessages invoke failed", t);
                    }
                    return false;
                });

                container.addView(button, 0);
            }
        );
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}
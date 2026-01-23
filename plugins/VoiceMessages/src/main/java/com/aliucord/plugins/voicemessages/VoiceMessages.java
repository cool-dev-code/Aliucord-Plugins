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

    private static final String VIEW_TAG = "aliucord_voice_message_btn";

    private boolean locked = false;
    private float startY = 0f;

    // Reflected Discord internals
    private Object voiceManager;
    private Method startRecording;
    private Method stopAndSendRecording;
    private Method lockRecording;

    @Override
    public void start(Context context) {

        try {
            Class<?> vmClass =
                Class.forName("com.discord.utilities.voice.VoiceMessageManager");

            Field instanceField = vmClass.getDeclaredField("INSTANCE");
            voiceManager = instanceField.get(null);

            startRecording = vmClass.getDeclaredMethod("startRecording");
            stopAndSendRecording = vmClass.getDeclaredMethod("stopAndSendRecording");
            lockRecording = vmClass.getDeclaredMethod("lockRecording");

        } catch (Throwable t) {
            logger.error("VoiceMessageManager not found", t);
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

                // Prevent duplicate view crash
                View existing = container.findViewWithTag(VIEW_TAG);
                if (existing != null) {
                    container.removeView(existing);
                }

                ImageButton mic = new ImageButton(context);
                mic.setTag(VIEW_TAG);
                mic.setImageDrawable(
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
                mic.setLayoutParams(lp);
                mic.setBackground(null);

                mic.setOnTouchListener((v, e) -> {
                    try {
                        switch (e.getAction()) {

                            case MotionEvent.ACTION_DOWN:
                                locked = false;
                                startY = e.getRawY();
                                startRecording.invoke(voiceManager);
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                float dy = startY - e.getRawY();
                                if (!locked && dy > DimenUtils.dpToPx(60)) {
                                    locked = true;
                                    lockRecording.invoke(voiceManager);
                                }
                                return true;

                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                if (!locked) {
                                    stopAndSendRecording.invoke(voiceManager);
                                }
                                return true;
                        }
                    } catch (Throwable t) {
                        logger.error("VoiceMessage invoke failed", t);
                    }
                    return false;
                });

                mic.setOnClickListener(v -> {
                    if (locked) {
                        try {
                            locked = false;
                            stopAndSendRecording.invoke(voiceManager);
                        } catch (Throwable t) {
                            logger.error("Stop recording failed", t);
                        }
                    }
                });

                container.addView(mic, 0);
            }
        );
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}

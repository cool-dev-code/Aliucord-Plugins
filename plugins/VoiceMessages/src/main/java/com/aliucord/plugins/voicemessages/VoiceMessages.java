package com.aliucord.plugins.voicemessages;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.core.content.ContextCompat;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Patcher;
import com.aliucord.utils.DimenUtils;
import com.discord.utilities.voice.VoiceMessageManager;
import com.lytefast.flexinput.fragment.FlexInputFragment;

@AliucordPlugin
public class VoiceMessages extends Plugin {

    private static final String VIEW_TAG = "aliucord_voice_message_button";

    private boolean locked = false;
    private float startY = 0f;

    @Override
    public void start(Context context) {

        Patcher.after(
                FlexInputFragment.class,
                "onViewCreated",
                View.class,
                android.os.Bundle.class,
                param -> {

                    View root = (View) param.args[0];
                    ViewGroup container = root.findViewById(
                            context.getResources().getIdentifier(
                                    "chat_input_container",
                                    "id",
                                    context.getPackageName()
                            )
                    );

                    if (container == null) return;

                    // 🔒 Remove previously injected button (prevents crash)
                    View old = container.findViewWithTag(VIEW_TAG);
                    if (old != null) container.removeView(old);

                    ImageButton micButton = new ImageButton(context);
                    micButton.setTag(VIEW_TAG);
                    micButton.setImageDrawable(
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
                    micButton.setLayoutParams(lp);
                    micButton.setBackground(null);

                    VoiceMessageManager voiceManager =
                            VoiceMessageManager.INSTANCE;

                    micButton.setOnTouchListener((v, event) -> {

                        switch (event.getAction()) {

                            case MotionEvent.ACTION_DOWN:
                                locked = false;
                                startY = event.getRawY();
                                voiceManager.startRecording();
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                float deltaY = startY - event.getRawY();
                                if (!locked && deltaY > DimenUtils.dpToPx(60)) {
                                    locked = true;
                                    voiceManager.lockRecording();
                                }
                                return true;

                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                if (!locked) {
                                    voiceManager.stopAndSendRecording();
                                }
                                return true;
                        }
                        return false;
                    });

                    // If locked, tapping stops recording
                    micButton.setOnClickListener(v -> {
                        if (locked) {
                            locked = false;
                            voiceManager.stopAndSendRecording();
                        }
                    });

                    container.addView(micButton, 0);
                }
        );
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}

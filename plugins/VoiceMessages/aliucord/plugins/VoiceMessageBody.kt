package com.aliucord.plugins

class VoiceMessageBody(
    val attachments: List<AttachmentBody.File>,
    val waveform: String,
    val duration_secs: Double
)

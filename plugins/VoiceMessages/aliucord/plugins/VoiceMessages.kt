package com.aliucord.plugins

class VoiceMessages(
    val content: String,
    val attachments: List<AttachmentBody>,
    val flags: Int,
    val voiceMessage: VoiceMessageBody
)

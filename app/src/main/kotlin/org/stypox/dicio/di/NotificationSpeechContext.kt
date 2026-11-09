package org.stypox.dicio.di

import org.dicio.skill.context.SpeechOutputDevice

/** Shared speech channel for notifications that can outlive the current input. */
interface NotificationSpeechContext {
    val notificationSpeechOutputDevice: SpeechOutputDevice
}

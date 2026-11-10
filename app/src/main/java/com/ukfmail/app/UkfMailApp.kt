package com.ukfmail.app

import android.app.Application

class UkfMailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        MailCheck.schedule(this)
        AisCheck.schedule(this)
    }
}

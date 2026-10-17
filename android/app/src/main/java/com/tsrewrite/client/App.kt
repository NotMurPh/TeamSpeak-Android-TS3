package com.tsrewrite.client

import android.app.Application

class App : Application() {
    lateinit var store: Store
        private set
    lateinit var session: Session
        private set
    lateinit var badges: BadgeStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        badges = BadgeStore(this)
        session = Session(this, store)
    }
}

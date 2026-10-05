package com.blushdesk.app

import android.app.Application
import com.blushdesk.app.di.AppContainer

class FergBentablesApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

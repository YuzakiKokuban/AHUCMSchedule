package com.yuzakikokuban.ahucm

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** No credential-protected storage: the service provider can start us before unlock. */
class ScheduleApplication : Application(), XposedServiceHelper.OnServiceListener {
    private val services = mutableSetOf<XposedService>()
    private val activation = MutableStateFlow(false)
    val moduleActivation: StateFlow<Boolean> = activation.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    @Synchronized override fun onServiceBind(service: XposedService) {
        services += service
        refreshModuleActivation()
    }

    @Synchronized override fun onServiceDied(service: XposedService) {
        services -= service
        refreshModuleActivation()
    }

    /** Refresh on foreground; never treat a persisted auto-management preference as activation. */
    @Synchronized fun refreshModuleActivation() {
        services.removeAll { service -> runCatching { service.apiVersion < 102 }.getOrDefault(true) }
        activation.value = services.isNotEmpty()
    }
}

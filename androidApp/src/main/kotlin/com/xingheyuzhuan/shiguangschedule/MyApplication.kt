package com.xingheyuzhuan.shiguangschedule

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import com.xingheyuzhuan.shiguangschedule.data.di.SharedModule
import com.xingheyuzhuan.shiguangschedule.widget.WidgetUnlockReceiver
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.plugin.module.dsl.startKoin

@Module(includes = [
    SharedModule::class
])
@ComponentScan("com.xingheyuzhuan.shiguangschedule")
class AppModule

@KoinApplication(modules = [AppModule::class])
class ScheduleAppConfig

class MyApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()

        startKoin<ScheduleAppConfig> {
            androidLogger()
            androidContext(this@MyApplication)
            workManagerFactory()
        }

        // USER_PRESENT 只能动态注册；使用 Application 上下文，在应用退到后台后继续监听。
        ContextCompat.registerReceiver(
            this,
            WidgetUnlockReceiver(),
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}

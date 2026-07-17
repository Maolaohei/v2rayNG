package com.v2ray.ang.xposed

import android.content.Context
import android.net.ConnectivityManager
import android.os.IBinder
import android.os.Parcel
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/**
 * Resolve the live IConnectivityManager binder the same way SFA does:
 * prefer ConnectivityManager.mService, then ServiceManager fallback.
 */
object ConnectivityBinderUtils {
    private const val TAG = "ConnectivityBinderUtils"

    fun getBinder(context: Context?): IBinder? {
        if (context != null) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    val field = cm.javaClass.getDeclaredField("mService")
                    field.isAccessible = true
                    val service = field.get(cm) as? android.os.IInterface
                    val binder = service?.asBinder()
                    if (binder != null) {
                        return binder
                    }
                }
            } catch (e: Throwable) {
                LogUtil.w(AppConfig.TAG, "$TAG: mService binder failed: ${e.message}")
            }
        }
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, Context.CONNECTIVITY_SERVICE) as? IBinder
        } catch (e: Throwable) {
            LogUtil.w(AppConfig.TAG, "$TAG: ServiceManager binder failed: ${e.message}")
            null
        }
    }

    inline fun <T> withParcel(block: (data: Parcel, reply: Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            block(data, reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

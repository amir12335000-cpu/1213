package ir.phonelink
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class StopShareReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent?){context.getSharedPreferences("alzahra_settings",Context.MODE_PRIVATE).edit().putBoolean("sharing_enabled",false).apply();context.stopService(Intent(context,ShareService::class.java))}}

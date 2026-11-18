package ir.phonelink

import android.app.*
import android.content.Intent
import android.os.*
import android.os.Environment
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ShareService : Service() {
    private val handler=Handler(Looper.getMainLooper())
    private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).build()
    private val prefs by lazy { getSharedPreferences("alzahra_settings",MODE_PRIVATE) }
    private val poll=object:Runnable{override fun run(){if(prefs.getBoolean("sharing_enabled",false)){pollCommands();handler.postDelayed(this,5000)}else stopSelf()}}
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){super.onCreate(); val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager; if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(NotificationChannel("sharing","اشتراک‌گذاری فایل",NotificationManager.IMPORTANCE_LOW)); val stop=Intent(this,StopShareReceiver::class.java); val pi=PendingIntent.getBroadcast(this,10,stop,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); val n=NotificationCompat.Builder(this,"sharing").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("اشتراک‌گذاری دانشگاه الزهرا فعال است").setContentText("فهرست و انتقال فایل‌های مجاز؛ برای توقف از این اعلان استفاده کنید.").setOngoing(true).addAction(0,"توقف اشتراک‌گذاری",pi).build(); startForeground(41,n) }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{handler.removeCallbacks(poll);handler.post(poll);return START_STICKY}
    override fun onDestroy(){handler.removeCallbacks(poll);http.dispatcher.cancelAll();super.onDestroy()}
    private fun base()=(prefs.getString("server_url","")?:"").trimEnd('/')
    private fun auth(url:String,body:JSONObject?=null):Request{val b=Request.Builder().url(url).header("Authorization","Bearer ${prefs.getString("device_token","")}");if(body!=null)b.post(body.toString().toRequestBody("application/json".toMediaType()))else b.get();return b.build()}
    private fun pollCommands(){http.newCall(auth("${base()}/api/phone/commands")).enqueue(object:Callback{override fun onFailure(c:Call,e:IOException){};override fun onResponse(c:Call,r:Response){val s=r.body?.string().orEmpty();if(!r.isSuccessful)return;try{val a=JSONArray(s);for(i in 0 until a.length()){val cmd=a.getJSONObject(i);when(cmd.optString("type")){"list"->sendList(cmd.getString("id"));"transfer"->sendFile(cmd)}}}catch(_:Exception){}}})}
    private fun scan():JSONArray{val out=JSONArray();val base=Environment.getExternalStorageDirectory().canonicalFile;var count=0;fun walk(d:File){if(count>=3000||!d.canRead())return;val children=d.listFiles()?:return;for(f in children){if(count>=3000)break;if(f.name=="Android"||f.name.startsWith("."))continue;if(f.isDirectory)walk(f)else if(f.isFile&&f.canRead()){try{val p=f.canonicalPath;if(p.startsWith(base.path+File.separator)){out.put(JSONObject().put("path",p).put("name",f.name).put("size",f.length()).put("modified",f.lastModified()));count++}}catch(_:Exception){}}}};walk(base);return out}
    private fun sendList(id:String){val req=auth("${base()}/api/phone/commands/$id/result",JSONObject().put("files",scan()));http.newCall(req).enqueue(object:Callback{override fun onFailure(c:Call,e:IOException){};override fun onResponse(c:Call,r:Response){r.close()}})}
    private fun sendFile(cmd:JSONObject){try{val root=Environment.getExternalStorageDirectory().canonicalFile;val f=File(cmd.getString("path")).canonicalFile;if(!f.path.startsWith(root.path+File.separator)||!f.isFile||!f.canRead())return;val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file",f.name,f.asRequestBody("application/octet-stream".toMediaType())).build();val req=Request.Builder().url("${base()}/api/phone/transfers/${cmd.getString("id")}/file").header("Authorization","Bearer ${prefs.getString("device_token","")}").post(body).build();http.newCall(req).enqueue(object:Callback{override fun onFailure(c:Call,e:IOException){};override fun onResponse(c:Call,r:Response){r.close()}})}catch(_:Exception){}}
}

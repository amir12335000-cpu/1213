package ir.phonelink

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("alzahra_settings", MODE_PRIVATE) }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private lateinit var status: TextView
    private lateinit var root: LinearLayout
    private var first: EditText? = null
    private var last: EditText? = null
    private var major: EditText? = null
    private var consent: CheckBox? = null
    private var waitingForStorage = false

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); if (waitingForStorage && Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) { waitingForStorage=false; render() } }

    private fun render() {
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,28,32,28) }
        fun label(t:String, size:Float=16f) { root.addView(TextView(this).apply { text=t; textSize=size; setPadding(0,10,0,10) }) }
        fun field(h:String, value:String=""):EditText { val e=EditText(this).apply { hint=h; setSingleLine(true); setText(value) }; root.addView(e,LinearLayout.LayoutParams(-1,-2)); return e }
        fun button(t:String, f:()->Unit) { root.addView(Button(this).apply { text=t; setOnClickListener { f() } }) }
        label("دانشگاه الزهرا",25f)
        status=TextView(this).apply { textSize=14f; setPadding(0,8,0,14) }; root.addView(status)

        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            waitingForStorage = true
            label("مرحله ۱: دسترسی گسترده به فایل‌های مشترک",19f)
            label("برای ادامه، مجوز رسمی اندروید را فعال کنید. این مجوز به فایل‌های مشترک قابل‌دسترسی محدود است و فایل‌های خصوصی برنامه‌های دیگر را باز نمی‌کند.")
            button("رفتن به تنظیمات دسترسی") {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_:Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
            status.text="پس از فعال‌کردن مجوز، به برنامه برگردید."
            setContentView(root); return
        }

        if (prefs.getString("device_token",null).isNullOrBlank()) {
            label("مرحله ۲: مشخصات دانشجو",19f)
            first=field("نام"); last=field("نام خانوادگی"); major=field("رشته تحصیلی")
            label("اتصال خودکار به سرور دانشگاه",18f)
            val configured = BuildConfig.SERVER_BASE_URL.trim().trimEnd('/')
            label(if (configured.isBlank()) "آدرس ثابت سرور هنوز در نسخه برنامه تنظیم نشده است. پس از راه‌اندازی هاست، آدرس HTTPS در تنظیمات ساخت برنامه قرار می‌گیرد." else "سرور دانشگاه پیکربندی شده است؛ برنامه هنگام ثبت‌نام خودکار متصل می‌شود.")
            label("با فعال‌سازی اشتراک‌گذاری، مدیران مجاز دانشگاه می‌توانند فهرست فایل‌های مشترک قابل‌دسترسی را ببینند و فایل انتخابی را دریافت کنند. اشتراک‌گذاری فقط در زمان فعال بودن سرویس و با اعلان دائمی انجام می‌شود و هر زمان می‌توانید آن را متوقف یا لغو کنید.",14f)
            consent=CheckBox(this).apply { text="این توضیحات را خواندم و با فعال‌سازی اشتراک‌گذاری موافقم" }; root.addView(consent)
            button("ثبت اطلاعات و اتصال خودکار") { enroll() }
            status.text=if(configured.isBlank()) "اتصال تا زمان تنظیم آدرس ثابت سرور غیرفعال است." else "ابتدا مجوز را فعال کنید؛ سپس اطلاعات و رضایت را ثبت کنید."
        } else {
            label("اشتراک‌گذاری دستگاه",19f)
            label("${prefs.getString("first_name","")} ${prefs.getString("last_name","")} — ${prefs.getString("major","")}")
            label("سرور: ${prefs.getString("server_url","")}")
            label(if(prefs.getBoolean("sharing_enabled",false)) "وضعیت: اشتراک‌گذاری فعال است؛ اعلان دائمی باید در نوار اعلان‌ها دیده شود." else "وضعیت: اشتراک‌گذاری متوقف است.")
            button(if(prefs.getBoolean("sharing_enabled",false)) "توقف و لغو اشتراک‌گذاری" else "شروع اشتراک‌گذاری") {
                if(prefs.getBoolean("sharing_enabled",false)) { revoke(); render() } else startSharing()
            }
            button("خروج و پاک‌کردن ثبت این دستگاه") { revoke(); prefs.edit().clear().apply(); render() }
            status.text="مدیر دانشگاه باید دستگاه جدید را در داشبورد تأیید کند. کد اتصال برای دانشجو لازم نیست."
        }
        setContentView(root)
    }

    private fun enroll() {
        val a=first?.text.toString().trim(); val b=last?.text.toString().trim(); val c=major?.text.toString().trim(); val u=BuildConfig.SERVER_BASE_URL.trim().trimEnd('/')
        if(a.isBlank()||b.isBlank()||c.isBlank()){status.text="نام، نام خانوادگی و رشته را کامل کنید.";return}
        if(!u.startsWith("https://")){status.text="آدرس ثابت HTTPS سرور هنوز در نسخه برنامه تنظیم نشده است. پس از راه‌اندازی هاست، نسخه نهایی ساخته می‌شود.";return}
        if(consent?.isChecked!=true){status.text="برای فعال‌سازی، رضایت آگاهانه را تأیید کنید.";return}
        val id=prefs.getString("device_id",null) ?: UUID.randomUUID().toString()
        val body=JSONObject().put("deviceId",id).put("firstName",a).put("lastName",b).put("major",c).toString().toRequestBody("application/json".toMediaType())
        status.text="در حال ثبت امن دستگاه…"
        http.newCall(Request.Builder().url("$u/api/phone/enroll").post(body).build()).enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){runOnUiThread{status.text="ثبت ناموفق: ${e.message}"}}
            override fun onResponse(call:Call,response:Response){val txt=response.body?.string().orEmpty();runOnUiThread{if(response.isSuccessful){val j=JSONObject(txt);prefs.edit().putString("device_id",id).putString("device_token",j.getString("token")).putString("server_url",u).putString("first_name",a).putString("last_name",b).putString("major",c).putBoolean("sharing_enabled",false).apply();render()}else status.text="سرور ثبت دستگاه را نپذیرفت: $txt"}}
        })
    }

    private fun startSharing() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){ requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),700); return }
        if(Build.VERSION.SDK_INT>=30 && !Environment.isExternalStorageManager()){render();return}
        prefs.edit().putBoolean("sharing_enabled",true).apply()
        val i=Intent(this,ShareService::class.java)
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i)
        render()
    }
    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray) { super.onRequestPermissionsResult(requestCode,permissions,grantResults); if(requestCode==700 && grantResults.isNotEmpty() && grantResults[0]==android.content.pm.PackageManager.PERMISSION_GRANTED) startSharing() else if(requestCode==700) status.text="برای نمایش اعلان دائمی، مجوز اعلان را فعال کنید." }

    private fun revoke() {
        prefs.edit().putBoolean("sharing_enabled",false).apply()
        stopService(Intent(this,ShareService::class.java))
        val u=prefs.getString("server_url","") ?: ""; val t=prefs.getString("device_token","") ?: ""
        if(u.isNotBlank()&&t.isNotBlank()) http.newCall(Request.Builder().url("$u/api/phone/revoke").header("Authorization","Bearer $t").post("{}".toRequestBody("application/json".toMediaType())).build()).enqueue(object:Callback{override fun onFailure(call:Call,e:IOException){};override fun onResponse(call:Call,response:Response){response.close()}})
        status.text="اشتراک‌گذاری متوقف شد."
    }
}

package com.khimaros.mimic

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Persistent Aurore bubble; user-defined entries call named scripts in Termux ~/bin. */
class FloatingMenu(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val prefs = service.getSharedPreferences("aurore_quick_menu", 0)
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var opened = false
    private var x = 8
    private var y = 220
    private val pink = Color.rgb(224, 132, 195)
    private val bg = Color.rgb(37, 40, 47)
    private val fg = Color.rgb(245, 245, 248)
    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()
    private fun shape(c: Int, r: Int) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat(); setStroke(dp(1), Color.argb(65,255,255,255))
    }

    fun show() {
        if (bubble != null) return
        val b = TextView(service).apply {
            text = "A"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = shape(pink, 50); contentDescription = "Menu rapide Aurore"; elevation = dp(8).toFloat()
        }
        val lp = WindowManager.LayoutParams(dp(52),dp(52),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.END; x=this@FloatingMenu.x; y=this@FloatingMenu.y }
        var sx=0f; var sy=0f; var bx=0; var by=0; var dragged=false
        b.setOnTouchListener { _, e -> when(e.action) {
            MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; bx=lp.x; by=lp.y; dragged=false; true }
            MotionEvent.ACTION_MOVE -> {
                val dx=(e.rawX-sx).toInt(); val dy=(e.rawY-sy).toInt()
                if(kotlin.math.abs(dx)>dp(5)||kotlin.math.abs(dy)>dp(5)) dragged=true
                if(dragged){ lp.x=(bx-dx).coerceAtLeast(0); lp.y=(by+dy).coerceAtLeast(dp(24)); x=lp.x; y=lp.y; try{wm.updateViewLayout(b,lp)}catch(_:Exception){} }
                true
            }
            MotionEvent.ACTION_UP -> { if(!dragged){ if(opened) close() else showPanel() }; true }
            else -> true
        } }
        try { wm.addView(b,lp); bubble=b } catch(_:Exception) { bubble=null }
    }

    fun remove() {
        close()
        bubble?.let { try { wm.removeView(it) } catch(_:Exception){} }
        bubble=null
    }
    private fun close() {
        panel?.let { try { wm.removeView(it) } catch(_:Exception){} }
        panel=null; opened=false
    }
    private fun showPanel() {
        if(opened) return
        val root=LinearLayout(service).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(12),dp(12),dp(12)); background=shape(bg,20); elevation=dp(12).toFloat() }
        val header=LinearLayout(service).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        header.addView(TextView(service).apply { text="AURORE · MENU RAPIDE"; textSize=15f; setTextColor(pink) },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("Fermer"){close()}); root.addView(header)
        val scroll=ScrollView(service); val list=LinearLayout(service).apply{orientation=LinearLayout.VERTICAL}
        addShortcut(list,"Extraire la page","extraire")
        addShortcut(list,"Analyser les ressources","aurore-trafic")
        list.addView(section("MES FONCTIONNALITÉS"))
        val entries=read()
        for(i in 0 until entries.length()){
            val item=entries.optJSONObject(i)?:continue
            val label=item.optString("label"); val script=item.optString("script")
            if(valid(script)){
                val row=LinearLayout(service).apply{orientation=LinearLayout.HORIZONTAL}
                row.addView(button(label){close();runScript(script)},LinearLayout.LayoutParams(0,-2,1f))
                row.addView(button("−"){
                    val kept=JSONArray()
                    for(j in 0 until entries.length()) if(j!=i) kept.put(entries.optJSONObject(j))
                    prefs.edit().putString("features",kept.toString()).apply(); close(); showPanel()
                }); list.addView(row)
            }
        }
        list.addView(section("AJOUTER UNE FONCTIONNALITÉ"))
        val label=EditText(service).apply{hint="Nom affiché";setSingleLine();textSize=14f;setTextColor(fg);setHintTextColor(Color.LTGRAY);background=shape(Color.rgb(53,57,66),10)}
        val script=EditText(service).apply{hint="Nom du script dans ~/bin";setSingleLine();textSize=14f;setTextColor(fg);setHintTextColor(Color.LTGRAY);background=shape(Color.rgb(53,57,66),10)}
        list.addView(label,params()); list.addView(script,params())
        list.addView(button("＋ Ajouter au menu"){
            val name=label.text.toString().trim(); val cmd=script.text.toString().trim()
            if(name.isBlank()||!valid(cmd)) toast("Indique un nom et un script simple situé dans ~/bin.")
            else if((0 until read().length()).any{read().optJSONObject(it)?.optString("script")==cmd}) toast("Ce script est déjà ajouté.")
            else { val a=read(); a.put(JSONObject().put("label",name.take(48)).put("script",cmd)); prefs.edit().putString("features",a.toString()).apply(); close(); showPanel(); toast("Fonctionnalité ajoutée.") }
        })
        list.addView(TextView(service).apply{text="Les scripts personnalisés doivent exister dans ~/bin. L’exécution passe par Termux et peut nécessiter l’autorisation des commandes externes.";textSize=11f;setTextColor(Color.LTGRAY);setPadding(0,dp(6),0,0)})
        scroll.addView(list); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val w=service.resources.displayMetrics.widthPixels; val h=service.resources.displayMetrics.heightPixels
        val lp=WindowManager.LayoutParams(minOf(dp(320),w-dp(20)),minOf(dp(560),h-dp(70)),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT)
            .apply{gravity=Gravity.TOP or Gravity.END;x=dp(6);y=(this@FloatingMenu.y-dp(12)).coerceAtLeast(dp(28))}
        try{wm.addView(root,lp);panel=root;opened=true}catch(_:Exception){toast("Impossible d’ouvrir le menu. Vérifie Mimic.")}
    }
    private fun addShortcut(parent:LinearLayout,label:String,script:String){parent.addView(button(label){close();runScript(script)})}
    private fun button(label:String,action:()->Unit)=Button(service).apply{text=label;textSize=13f;isAllCaps=false;setTextColor(fg);background=shape(Color.rgb(53,57,66),10);setPadding(dp(6),dp(2),dp(6),dp(2));setOnClickListener{action()}}
    private fun section(s:String)=TextView(service).apply{text=s;textSize=11f;setTextColor(pink);setPadding(0,dp(10),0,dp(4))}
    private fun params()=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4);bottomMargin=dp(4)}
    private fun valid(s:String)=s.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))&&s!="."&&s!=".."
    private fun read()=try{JSONArray(prefs.getString("features","[]")?:"[]")}catch(_:Exception){JSONArray()}
    private fun runScript(name:String){
        if(!valid(name)){toast("Nom de script invalide.");return}
        val intent=Intent("com.termux.RUN_COMMAND").apply{
            setPackage("com.termux")
            putExtra("com.termux.RUN_COMMAND_PATH","/data/data/com.termux/files/home/bin/$name")
            putExtra("com.termux.RUN_COMMAND_WORKDIR","/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND",false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try{service.startService(intent);toast("Commande envoyée à Termux : $name")}
        catch(_:SecurityException){toast("Autorise les commandes externes dans Termux puis réessaie.")}
        catch(_:Exception){toast("Termux n’a pas accepté la commande. Vérifie ses réglages externes.")}
    }
    private fun toast(s:String)=Toast.makeText(service,s,Toast.LENGTH_LONG).show()
}
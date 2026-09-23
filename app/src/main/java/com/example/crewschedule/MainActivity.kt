package com.example.crewschedule

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GridView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.concurrent.thread

private const val STATUS_YES = "yes"
private const val STATUS_NO = "no"
private const val STATUS_UNKNOWN = "unknown"

private data class Project(val id: String, val name: String, val description: String = "")
private data class ScheduleState(val projects: List<Project> = emptyList(), val statuses: Map<String, Map<String, String>> = emptyMap())
private enum class Mode { WEEK, CALENDAR }

private class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("crew_schedule", Context.MODE_PRIVATE)
    fun load(): ScheduleState {
        val raw = prefs.getString("state", null) ?: return ScheduleState()
        return runCatching {
            val root = JSONObject(raw)
            val projects = buildList {
                val a = root.optJSONArray("projects") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    add(Project(p.getString("id"), p.getString("name"), p.optString("description")))
                }
            }
            val statuses = buildMap {
                val o = root.optJSONObject("statuses") ?: JSONObject()
                for (pid in o.keys()) {
                    val days = o.getJSONObject(pid)
                    put(pid, buildMap { for (d in days.keys()) put(d, days.getString(d)) })
                }
            }
            ScheduleState(projects, statuses)
        }.getOrDefault(ScheduleState())
    }
    fun save(state: ScheduleState) {
        val root = JSONObject()
        val projects = JSONArray()
        state.projects.forEach { p -> projects.put(JSONObject().put("id", p.id).put("name", p.name).put("description", p.description)) }
        val statuses = JSONObject()
        state.statuses.forEach { (pid, days) ->
            val o = JSONObject(); days.forEach { (d, s) -> o.put(d, s) }; statuses.put(pid, o)
        }
        root.put("projects", projects).put("statuses", statuses)
        prefs.edit().putString("state", root.toString()).apply()
    }
}

private class SyncClient {
    private val url = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_ANON_KEY
    private val id = BuildConfig.SCHEDULE_ID
    val configured get() = url.isNotBlank() && key.isNotBlank()

    fun pull(onResult: (ScheduleState?) -> Unit) = thread {
        if (!configured) { onResult(null); return@thread }
        try {
            val conn = URL("$url/rest/v1/crew_schedule?id=eq.$id&select=payload,updated_at").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"; conn.setRequestProperty("apikey", key); conn.setRequestProperty("Authorization", "Bearer $key")
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val rows = JSONArray(body)
                if (rows.length() > 0) onResult(parsePayload(rows.getJSONObject(0).getJSONObject("payload"))) else onResult(null)
            } else onResult(null)
            conn.disconnect()
        } catch (_: Exception) { onResult(null) }
    }

    fun push(state: ScheduleState) = thread {
        if (!configured) return@thread
        try {
            val payload = state.toJson()
            val body = JSONObject().put("id", id).put("payload", payload).toString()
            val conn = URL("$url/rest/v1/crew_schedule?on_conflict=id").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("apikey", key); conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("Prefer", "resolution=merge-duplicates")
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.inputStream.close(); conn.disconnect()
        } catch (_: Exception) { }
    }

    private fun parsePayload(o: JSONObject): ScheduleState {
        val projects = buildList {
            val a = o.optJSONArray("projects") ?: JSONArray()
            for (i in 0 until a.length()) { val p=a.getJSONObject(i); add(Project(p.getString("id"),p.getString("name"),p.optString("description"))) }
        }
        val statuses = buildMap {
            val s=o.optJSONObject("statuses") ?: JSONObject()
            for(pid in s.keys()) { val d=s.getJSONObject(pid); put(pid, buildMap { for(k in d.keys()) put(k,d.getString(k)) }) }
        }
        return ScheduleState(projects,statuses)
    }
}

private fun ScheduleState.toJson(): JSONObject {
    val p = JSONArray(); projects.forEach { p.put(JSONObject().put("id",it.id).put("name",it.name).put("description",it.description)) }
    val s=JSONObject(); statuses.forEach { (pid,days)-> val d=JSONObject(); days.forEach { (k,v)->d.put(k,v) }; s.put(pid,d) }
    return JSONObject().put("projects",p).put("statuses",s)
}

private fun currentMonday(date: LocalDate = LocalDate.now()) = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
private fun weekLabel(monday: LocalDate): String {
    val friday=monday.plusDays(4)
    val fmt=DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    return if (monday.year == friday.year && monday.month == friday.month) "${monday.format(DateTimeFormatter.ofPattern("MMM d",Locale.US))}–${friday.dayOfMonth}, ${friday.year}" else "${monday.format(fmt)}–${friday.format(fmt)}"
}
private fun dayKey(d: LocalDate) = d.toString()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { CrewScheduleApp(this) } }
}

@Composable
private fun CrewScheduleApp(context: Context) {
    val store=remember { LocalStore(context) }; val sync=remember { SyncClient() }
    var state by remember { mutableStateOf(store.load()) }; var mode by remember { mutableStateOf(Mode.WEEK) }
    var monday by remember { mutableStateOf(currentMonday()) }; var dialog by remember { mutableStateOf<Project?>(null) }; var adding by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf<Project?>(null) }
    var syncText by remember { mutableStateOf(if(sync.configured) "● Synced" else "● Local only") }

    fun save(newState: ScheduleState) { state=newState; store.save(newState); syncText=if(sync.configured) "↻ Syncing…" else "● Local only"; sync.push(newState) }
    LaunchedEffect(Unit) {
        fun poll() { sync.pull { remote -> if(remote!=null) { store.save(remote); state=remote; syncText="● Synced · ${java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a",Locale.US))}" } } }
        poll(); val h=Handler(Looper.getMainLooper()); val r=object:Runnable{override fun run(){poll();h.postDelayed(this,15000)}}; h.postDelayed(r,15000)
    }

    MaterialTheme(colorScheme=darkScheme) {
        Surface(Modifier.fillMaxSize(), color=Color(0xFF0B1018)) {
            Column(Modifier.fillMaxSize()) {
                TopBar(mode, {mode=it}, syncText)
                WeekNavigator(monday, { monday=monday.minusWeeks(1) }, { monday=currentMonday() }, { monday=monday.plusWeeks(1) })
                when(mode) {
                    Mode.WEEK -> WeekMode(state,monday,{id,date ->
                        val p=state.projects.firstOrNull{it.id==id} ?: return@WeekMode
                        val old=state.statuses[id]?.get(dayKey(date)); val next=when(old){STATUS_YES->STATUS_NO;STATUS_NO->STATUS_UNKNOWN;else->STATUS_YES}
                        val statuses=state.statuses.toMutableMap(); val days=(statuses[id]?.toMutableMap()?:mutableMapOf()); days[dayKey(date)]=next; statuses[id]=days; save(state.copy(statuses=statuses))
                    },{dialog=it}) { adding=true }
                    Mode.CALENDAR -> CalendarMode(state,monday,{dialog=it})
                }
            }
        }
    }
    if(adding) ProjectDialog(null,{adding=false},{name,desc->
        val p=Project(java.util.UUID.randomUUID().toString(),name.trim(),desc.trim()); save(state.copy(projects=(state.projects+p).sortedBy{it.name.lowercase()})); adding=false
    })
    dialog?.let { p -> ProjectDialog(p,{dialog=null},{name,desc-> val np=p.copy(name=name.trim(),description=desc.trim()); val projects=state.projects.map{if(it.id==p.id)np else it}.sortedBy{it.name.lowercase()}; save(state.copy(projects=projects)); dialog=null },{delete=p;dialog=null}) }
    delete?.let { p -> AlertDialog(onDismissRequest={delete=null},title={Text("Delete this project?")},text={Text("This removes its schedule information too.")},confirmButton={TextButton(onClick={val ns=state.copy(projects=state.projects.filterNot{it.id==p.id},statuses=state.statuses.filterKeys { it != p.id });save(ns);delete=null}){Text("OK")}},dismissButton={TextButton(onClick={delete=null}){Text("Cancel")}}) }
}

@Composable private fun TopBar(mode:Mode,onMode:(Mode)->Unit,syncText:String) {
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Column { Text("Crew Schedule",fontSize=22.sp,fontWeight=FontWeight.Bold); Text(syncText,fontSize=12.sp,color=Color(0xFF91A0B3)) }
        var expanded by remember{mutableStateOf(false)}
        Box { IconButton(onClick={expanded=true}){Icon(if(mode==Mode.WEEK)Icons.Default.GridView else Icons.Default.CalendarMonth,"Mode")} ; DropdownMenu(expanded,{expanded=false}) { DropdownMenuItem(text={Text("Week Mode")},onClick={onMode(Mode.WEEK);expanded=false}); DropdownMenuItem(text={Text("Calendar Mode")},onClick={onMode(Mode.CALENDAR);expanded=false}) } }
    }
}

@Composable private fun WeekNavigator(monday:LocalDate,prev:()->Unit,today:()->Unit,next:()->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=prev){Icon(Icons.Default.ChevronLeft,"Previous week")}
        Text(weekLabel(monday),Modifier.weight(1f),fontWeight=FontWeight.SemiBold,fontSize=17.sp)
        TextButton(onClick=today){Text("Today")}; IconButton(onClick=next){Icon(Icons.Default.ChevronRight,"Next week")}
    }
}

@Composable private fun WeekMode(state:ScheduleState,monday:LocalDate,onDay:(String,LocalDate)->Unit,onProject:(Project)->Unit,onAdd:()->Unit) {
    val days=(0..4).map{monday.plusDays(it.toLong())}; val scroll=rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Box(Modifier.width(145.dp).height(50.dp),contentAlignment=Alignment.CenterStart){IconButton(onClick=onAdd){Icon(Icons.Default.Add,"Add project")}}
            days.forEach { d -> Box(Modifier.width(76.dp).height(50.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT,Locale.US).uppercase(),fontWeight=FontWeight.Bold,fontSize=12.sp);Text(d.dayOfMonth.toString(),fontSize=11.sp,color=Color(0xFF8997AA))}} }
        }
        Divider(color=Color(0xFF263244))
        state.projects.forEach { p ->
            Row(Modifier.fillMaxWidth().horizontalScroll(scroll).height(68.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.width(145.dp).clickable{onProject(p)}.padding(end=8.dp)){Text(p.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold);if(p.description.isNotBlank())Text(p.description,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=11.sp,color=Color(0xFF8794A8))}
                days.forEach { d -> StatusCell(state.statuses[p.id]?.get(dayKey(d))?:STATUS_UNKNOWN){onDay(p.id,d)} }
            }
            Divider(color=Color(0xFF1D2735))
        }
        if(state.projects.isEmpty()) Text("Add your first project with +",Modifier.padding(24.dp),color=Color(0xFF93A1B5))
    }
}

@Composable private fun StatusCell(status:String,onClick:()->Unit) {
    val color=when(status){STATUS_YES->Color(0xFF36D18A);STATUS_NO->Color(0xFFE56B6F);else->Color(0xFFE4B55A)}
    Box(Modifier.width(76.dp).height(56.dp).padding(6.dp).border(1.dp,Color(0xFF2A3545),RoundedCornerShape(10.dp)).background(color.copy(alpha=.12f),RoundedCornerShape(10.dp)).clickable{onClick()},contentAlignment=Alignment.Center){Text(if(status==STATUS_YES)"✓" else if(status==STATUS_NO)"X" else "?",fontSize=22.sp,fontWeight=FontWeight.Bold,color=color)}
}

@Composable private fun CalendarMode(state:ScheduleState,monday:LocalDate,onProject:(Project)->Unit) {
    val days=(0..4).map{monday.plusDays(it.toLong())}; val vertical=rememberScrollState()
    Row(Modifier.fillMaxSize().verticalScroll(vertical).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        days.forEach { d ->
            val scheduled=state.projects.filter{state.statuses[it.id]?.get(dayKey(d))==STATUS_YES}
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT,Locale.US).uppercase(),Modifier.fillMaxWidth().padding(vertical=8.dp),fontWeight=FontWeight.Bold,fontSize=12.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                Text(d.dayOfMonth.toString(),Modifier.fillMaxWidth().padding(bottom=8.dp),fontSize=20.sp,fontWeight=FontWeight.Bold,textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=Color(0xFF8CA7C7))
                scheduled.forEach { p ->
                    Surface(shape=RoundedCornerShape(8.dp),color=Color(0xFF173C2E),modifier=Modifier.fillMaxWidth().padding(bottom=6.dp).clickable{onProject(p)}) { Text(p.name,Modifier.padding(horizontal=7.dp,vertical=9.dp),fontSize=12.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,color=Color(0xFF70E5A8)) }
                }
            }
        }
    }
}

@Composable private fun ProjectDialog(project:Project?,onCancel:()->Unit,onSave:(String,String)->Unit,onDelete:(()->Unit)?=null) {
    var name by remember(project){mutableStateOf(project?.name?:(""))}; var desc by remember(project){mutableStateOf(project?.description?:"")}
    AlertDialog(onDismissRequest=onCancel,title={Text(if(project==null)"Add Project" else "Edit Project")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(name,{name=it},label={Text("Project")},singleLine=true);OutlinedTextField(desc,{desc=it},label={Text("Description (optional)")},minLines=2)}},confirmButton={TextButton(enabled=name.trim().isNotEmpty(),onClick={onSave(name,desc)}){Text("Save")}},dismissButton={Row{if(onDelete!=null)TextButton(onClick=onDelete){Text("Delete",color=Color(0xFFE56B6F))};TextButton(onClick=onCancel){Text("Cancel")}}})
}

private val darkScheme=androidx.compose.material3.darkColorScheme(primary=Color(0xFF5AA9FF),secondary=Color(0xFF78B8FF),background=Color(0xFF0B1018),surface=Color(0xFF121923),surfaceVariant=Color(0xFF1B2634),onSurface=Color(0xFFE8EEF7))

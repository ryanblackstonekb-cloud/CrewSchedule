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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
import java.time.YearMonth
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun report(onResult: (ScheduleState?, String?) -> Unit, state: ScheduleState?, error: String?) {
        mainHandler.post { onResult(state, error) }
    }

    fun pull(onResult: (ScheduleState?, String?) -> Unit) = thread {
        if (!configured) {
            report(onResult, null, "Sync not configured")
            return@thread
        }
        try {
            val conn = URL("$url/rest/v1/crew_schedule?id=eq.$id&select=payload,updated_at").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer $key")
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val rows = JSONArray(body)
                val remote = if (rows.length() > 0) parsePayload(rows.getJSONObject(0).getJSONObject("payload")) else null
                report(onResult, remote, null)
            } else {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(180).orEmpty()
                val message = "HTTP " + code + if (detail.isNotBlank()) ": " + detail else ""
                report(onResult, null, message)
            }
            conn.disconnect()
        } catch (e: Exception) {
            report(onResult, null, e.message ?: "Connection failed")
        }
    }

    fun push(state: ScheduleState, onResult: (Boolean, String?) -> Unit) = thread {
        if (!configured) {
            mainHandler.post { onResult(false, "Sync not configured") }
            return@thread
        }
        try {
            val payload = state.toJson()
            val body = JSONObject()
                .put("id", id)
                .put("payload", payload)
                .put("updated_at", java.time.Instant.now().toString())
                .toString()
            val conn = URL("$url/rest/v1/crew_schedule?on_conflict=id").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) {
                mainHandler.post { onResult(true, null) }
            } else {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(180).orEmpty()
                val message = "HTTP " + code + if (detail.isNotBlank()) ": " + detail else ""
                mainHandler.post { onResult(false, message) }
            }
            conn.disconnect()
        } catch (e: Exception) {
            mainHandler.post { onResult(false, e.message ?: "Connection failed") }
        }
    }

    private fun parsePayload(o: JSONObject): ScheduleState {
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
    var monday by remember { mutableStateOf(currentMonday()) }; var calendarMonth by remember { mutableStateOf(YearMonth.now()) }; var dialog by remember { mutableStateOf<Project?>(null) }; var adding by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf<Project?>(null) }
    var syncText by remember { mutableStateOf(if(sync.configured) "● Syncing…" else "● Local only") }
    var isRefreshing by remember { mutableStateOf(false) }

    fun finishPull(remote: ScheduleState?, error: String?, fromRefresh: Boolean = false) {
        if (remote != null) {
            store.save(remote)
            state = remote
            syncText = "● Synced · " + java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a",Locale.US))
        } else if (error != null) {
            syncText = if (error == "Sync not configured") "● Local only" else "● Sync error"
        }
        if (fromRefresh) isRefreshing = false
    }

    fun pullNow(fromRefresh: Boolean = false) {
        if (fromRefresh) isRefreshing = true
        if (!sync.configured) {
            syncText = "● Local only"
            isRefreshing = false
            return
        }
        syncText = "↻ Syncing…"
        sync.pull { remote, error -> finishPull(remote, error, fromRefresh) }
    }

    fun save(newState: ScheduleState) {
        state = newState
        store.save(newState)
        syncText = if (sync.configured) "↻ Syncing…" else "● Local only"
        sync.push(newState) { ok, error ->
            if (ok) {
                syncText = "● Synced · " + java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a",Locale.US))
            } else if (error == "Sync not configured") {
                syncText = "● Local only"
            } else {
                syncText = "● Sync error"
            }
        }
    }

    LaunchedEffect(Unit) {
        pullNow()
        val h=Handler(Looper.getMainLooper())
        val r=object:Runnable{override fun run(){pullNow();h.postDelayed(this,15000)}}
        h.postDelayed(r,15000)
    }
    MaterialTheme(colorScheme=darkScheme) {
        Surface(Modifier.fillMaxSize(), color=Color(0xFF0B1018)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopBar(mode, {mode=it}, syncText)
                if (mode == Mode.WEEK) {
                    WeekNavigator(monday, { monday=monday.minusWeeks(1) }, { monday=currentMonday() }, { monday=monday.plusWeeks(1) })
                } else {
                    CalendarNavigator(calendarMonth, { calendarMonth=calendarMonth.minusMonths(1) }, { calendarMonth=YearMonth.now() }, { calendarMonth=calendarMonth.plusMonths(1) })
                }
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { pullNow(true) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    when(mode) {
                        Mode.WEEK -> WeekMode(state,monday,{id,date,status ->
                            val statuses=state.statuses.toMutableMap()
                            val days=(statuses[id]?.toMutableMap()?:mutableMapOf())
                            days[dayKey(date)]=status
                            statuses[id]=days
                            save(state.copy(statuses=statuses))
                        },{dialog=it}) { adding=true }
                        Mode.CALENDAR -> CalendarMode(state,calendarMonth,{dialog=it})
                    }
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
        IconButton(onClick=next){Icon(Icons.Default.ChevronRight,"Next week")}; TextButton(onClick=today){Text("Today")}
    }
}

@Composable private fun WeekMode(state:ScheduleState,monday:LocalDate,onDay:(String,LocalDate,String)->Unit,onProject:(Project)->Unit,onAdd:()->Unit) {
    val days=(0..4).map{monday.plusDays(it.toLong())}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=8.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.width(104.dp).fillMaxHeight().border(0.5.dp,Color(0xFF334255)).drawBehind { drawLine(Color(0xFF334255), Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f) },contentAlignment=Alignment.CenterStart){
                IconButton(onClick=onAdd){Icon(Icons.Default.Add,"Add project")}
            }
            days.forEach { d ->
                Column(
                    Modifier.weight(1f).fillMaxHeight().border(0.5.dp,Color(0xFF334255)),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.Center
                ) {
                    Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT,Locale.US).uppercase(),fontWeight=FontWeight.Bold,fontSize=11.sp)
                    Text(d.dayOfMonth.toString(),fontSize=11.sp,color=Color(0xFF8997AA))
                }
            }
        }
        Divider(color=Color(0xFF263244))
        state.projects.forEach { p ->
            Row(Modifier.fillMaxWidth().height(68.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.width(104.dp).fillMaxHeight().drawBehind { drawLine(Color(0xFF334255), Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f) }.clickable{onProject(p)}.padding(end=6.dp), verticalArrangement=Arrangement.Center){
                    Text(p.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold,fontSize=14.sp)
                    if(p.description.isNotBlank()) Text(p.description,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=10.sp,color=Color(0xFF8794A8))
                }
                days.forEach { d ->
                    StatusCell(
                        status=state.statuses[p.id]?.get(dayKey(d)) ?: STATUS_UNKNOWN,
                        modifier=Modifier.weight(1f),
                        onStatusChange={status -> onDay(p.id,d,status)}
                    )
                }
            }
            Divider(color=Color(0xFF1D2735))
        }
        if(state.projects.isEmpty()) Text("Add your first project with +",Modifier.padding(24.dp),color=Color(0xFF93A1B5))
    }
}

@Composable private fun StatusCell(status:String,modifier:Modifier,onStatusChange:(String)->Unit) {
    val color=when(status){STATUS_YES->Color(0xFF36D18A);STATUS_NO->Color(0xFFE56B6F);else->Color(0xFFE4B55A)}
    var expanded by remember { mutableStateOf(false) }
    val symbol=when(status){STATUS_YES->"✓";STATUS_NO->"X";else->"?"}
    Box(
        modifier.height(56.dp).padding(4.dp),
        contentAlignment=Alignment.Center
    ) {
        Box(
            Modifier.fillMaxSize()
                .border(1.dp,Color(0xFF2A3545),RoundedCornerShape(10.dp))
                .background(color.copy(alpha=.12f),RoundedCornerShape(10.dp))
                .clickable{expanded=true},
            contentAlignment=Alignment.Center
        ) {
            Text(symbol,fontSize=21.sp,fontWeight=FontWeight.Bold,color=color)
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            DropdownMenuItem(
                text={Row(verticalAlignment=Alignment.CenterVertically){Text("✓",color=Color(0xFF36D18A),fontSize=22.sp,fontWeight=FontWeight.Bold); Spacer(Modifier.width(10.dp)); Text("Scheduled",color=Color(0xFF36D18A),fontWeight=FontWeight.SemiBold)}},
                onClick={onStatusChange(STATUS_YES);expanded=false}
            )
            DropdownMenuItem(
                text={Row(verticalAlignment=Alignment.CenterVertically){Text("X",color=Color(0xFFE56B6F),fontSize=21.sp,fontWeight=FontWeight.Bold); Spacer(Modifier.width(10.dp)); Text("Not scheduled",color=Color(0xFFE56B6F),fontWeight=FontWeight.SemiBold)}},
                onClick={onStatusChange(STATUS_NO);expanded=false}
            )
            DropdownMenuItem(
                text={Row(verticalAlignment=Alignment.CenterVertically){Text("?",color=Color(0xFFE4B55A),fontSize=22.sp,fontWeight=FontWeight.Bold); Spacer(Modifier.width(10.dp)); Text("Unknown",color=Color(0xFFE4B55A),fontWeight=FontWeight.SemiBold)}},
                onClick={onStatusChange(STATUS_UNKNOWN);expanded=false}
            )
        }
    }
}

@Composable private fun CalendarNavigator(month:YearMonth,prev:()->Unit,today:()->Unit,next:()->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=prev){Icon(Icons.Default.ChevronLeft,"Previous month")}
        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy",Locale.US)),Modifier.weight(1f),fontWeight=FontWeight.SemiBold,fontSize=17.sp)
        IconButton(onClick=next){Icon(Icons.Default.ChevronRight,"Next month")}; TextButton(onClick=today){Text("Today")}
    }
}

@Composable private fun CalendarMode(state:ScheduleState,month:YearMonth,onProject:(Project)->Unit) {
    // Five-column work calendar: Monday through Friday only. Weekend dates are
    // intentionally omitted, while adjacent-month weekdays remain visible in gray.
    val firstDay=month.atDay(1)
    val lastDay=month.atEndOfMonth()
    val firstMonday=firstDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val lastMonday=lastDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekStarts=generateSequence(firstMonday){it.plusWeeks(1)}
        .takeWhile{!it.isAfter(lastMonday)}
        .toList()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=6.dp)) {
        Row(Modifier.fillMaxWidth().height(38.dp)) {
            listOf(DayOfWeek.MONDAY,DayOfWeek.TUESDAY,DayOfWeek.WEDNESDAY,DayOfWeek.THURSDAY,DayOfWeek.FRIDAY).forEach { dow ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().border(0.5.dp,Color(0xFF334255)),
                    contentAlignment=Alignment.Center
                ) {
                    Text(dow.getDisplayName(TextStyle.SHORT,Locale.US).uppercase(),fontWeight=FontWeight.Bold,fontSize=11.sp)
                }
            }
        }
        weekStarts.forEach { monday ->
            val daySchedules=(0..4).map { offset ->
                val d=monday.plusDays(offset.toLong())
                state.projects.filter{state.statuses[it.id]?.get(dayKey(d))==STATUS_YES}
            }
            val maxProjects=daySchedules.maxOfOrNull{it.size} ?: 0
            // Each Monday-Friday week row gets only as tall as its busiest day.
            // Other weeks remain compact instead of expanding to match it.
            val rowHeight=(if (maxProjects == 0) 104 else 74 + ((maxProjects-1) * 45)).dp

            Row(Modifier.fillMaxWidth().height(rowHeight)) {
                (0..4).forEach { offset ->
                    val d=monday.plusDays(offset.toLong())
                    val inMonth=d.year==month.year && d.month==month.month
                    val scheduled=daySchedules[offset]
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .border(0.5.dp,Color(0xFF334255))
                            .background(if(inMonth) Color.Transparent else Color(0xFF080D14))
                            .padding(4.dp)
                    ) {
                        Text(
                            d.dayOfMonth.toString(),
                            Modifier.fillMaxWidth(),
                            fontSize=13.sp,
                            fontWeight=FontWeight.Bold,
                            color=if(inMonth) Color(0xFFE8EEF7) else Color(0xFF566274),
                            textAlign=androidx.compose.ui.text.style.TextAlign.Center
                        )
                        scheduled.forEach { p ->
                            Box(
                                Modifier.fillMaxWidth().height(42.dp).padding(top=3.dp)
                                    .border(1.dp,Color(0xFF2B7252),RoundedCornerShape(6.dp))
                                    .background(Color(0xFF173C2E),RoundedCornerShape(6.dp))
                                    .clickable{onProject(p)},
                                contentAlignment=Alignment.Center
                            ) {
                                Text(
                                    p.name,
                                    Modifier.padding(horizontal=3.dp),
                                    fontSize=10.sp,
                                    fontWeight=FontWeight.SemiBold,
                                    maxLines=2,
                                    overflow=TextOverflow.Ellipsis,
                                    textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                                    color=Color(0xFF70E5A8)
                                )
                            }
                        }
                    }
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

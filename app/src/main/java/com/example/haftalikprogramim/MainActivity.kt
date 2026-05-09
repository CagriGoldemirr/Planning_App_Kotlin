package com.example.haftalikprogramim

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.absoluteValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HavaliHaftalikProgram()
        }
    }
}


data class Gorev(val metin: String, val tamamlandi: Boolean = false)
data class GunBilgisi(
    val gunAdi: String,
    val ekranTarihi: String,
    val yil: String,
    val ay: String,
    val gun: String,
    val gelecekteMi: Boolean
)


fun verileriKaydet(context: Context, haftalikGorevler: Map<String, List<Gorev>>) {
    val prefs = context.getSharedPreferences("PlanPrefs", Context.MODE_PRIVATE)
    val rootObj = JSONObject()

    haftalikGorevler.forEach { (gun, liste) ->
        val jsonArray = JSONArray()
        liste.forEach { gorev ->
            val gorevObj = JSONObject()
            gorevObj.put("metin", gorev.metin)
            gorevObj.put("tamamlandi", gorev.tamamlandi)
            jsonArray.put(gorevObj)
        }
        rootObj.put(gun, jsonArray)
    }

    prefs.edit().putString("gorev_verileri", rootObj.toString()).apply()
}

fun verileriYukle(context: Context): Map<String, List<Gorev>> {
    val prefs = context.getSharedPreferences("PlanPrefs", Context.MODE_PRIVATE)
    val jsonString = prefs.getString("gorev_verileri", null) ?: return emptyMap()

    val map = mutableMapOf<String, List<Gorev>>()
    val rootObj = JSONObject(jsonString)

    val keys = rootObj.keys()
    while (keys.hasNext()) {
        val gun = keys.next()
        val jsonArray = rootObj.getJSONArray(gun)
        val liste = mutableListOf<Gorev>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            liste.add(Gorev(obj.getString("metin"), obj.getBoolean("tamamlandi")))
        }
        map[gun] = liste
    }
    return map
}


suspend fun wikipediaMakaleGetir(yil: String, ay: String, gun: String): Triple<String, String, String>? {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("https://en.wikipedia.org/api/rest_v1/feed/featured/$yil/$ay/$gun")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(response)
                val tfa = json.getJSONObject("tfa")

                return@withContext Triple(
                    tfa.getString("normalizedtitle"),
                    tfa.getString("extract"),
                    tfa.getJSONObject("content_urls").getJSONObject("desktop").getString("page")
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}

fun getHaftaninTarihleri(): List<GunBilgisi> {
    val takvim = Calendar.getInstance()
    val bugun = Calendar.getInstance()
    takvim.firstDayOfWeek = Calendar.MONDAY
    takvim.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

    val gunlerListesi = mutableListOf<GunBilgisi>()
    val gunİsimleri = listOf("PAZARTESİ", "SALI", "ÇARŞAMBA", "PERŞEMBE", "CUMA", "CUMARTESİ", "PAZAR")

    for (i in 0..6) {
        val gelecekteMi = takvim.get(Calendar.YEAR) > bugun.get(Calendar.YEAR) ||
                (takvim.get(Calendar.YEAR) == bugun.get(Calendar.YEAR) && takvim.get(Calendar.DAY_OF_YEAR) > bugun.get(Calendar.DAY_OF_YEAR))

        gunlerListesi.add(GunBilgisi(
            gunAdi = gunİsimleri[i],
            ekranTarihi = SimpleDateFormat("dd MMM", Locale("tr")).format(takvim.time),
            yil = SimpleDateFormat("yyyy", Locale("tr")).format(takvim.time),
            ay = SimpleDateFormat("MM", Locale("tr")).format(takvim.time),
            gun = SimpleDateFormat("dd", Locale("tr")).format(takvim.time),
            gelecekteMi = gelecekteMi
        ))
        takvim.add(Calendar.DAY_OF_MONTH, 1)
    }
    return gunlerListesi
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HavaliHaftalikProgram() {
    val context = LocalContext.current
    val haftaninGunleri = remember { getHaftaninTarihleri() }

    // Hafıza yükleme: Uygulama açıldığında verileri hafızadan çeker
    var haftalikGorevler by remember { mutableStateOf(verileriYukle(context)) }
    var uyariGoster by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { haftaninGunleri.size })

    // Veri her değiştiğinde otomatik kaydetme
    LaunchedEffect(haftalikGorevler) {
        verileriKaydet(context, haftalikGorevler)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "arkaplan")
    val renk1 by infiniteTransition.animateColor(
        initialValue = Color(0xFF0B192C),
        targetValue = Color(0xFF1E1E3F),
        animationSpec = infiniteRepeatable(animation = tween(4000, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label = "renk1"
    )
    val renk2 by infiniteTransition.animateColor(
        initialValue = Color(0xFF050B14),
        targetValue = Color(0xFF0B192C),
        animationSpec = infiniteRepeatable(animation = tween(6000, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label = "renk2"
    )

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(renk1, renk2)))) {
        MatrixArkaPlan()

        IconButton(
            onClick = { uyariGoster = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 24.dp).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Sıfırla", tint = Color(0xFFFF5252))
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(top = 80.dp),
            contentPadding = PaddingValues(horizontal = 32.dp)
        ) { page ->
            val sayfaSapmasi = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val gun = haftaninGunleri[page]

            Card(
                modifier = Modifier.fillMaxHeight(0.85f).fillMaxWidth().graphicsLayer {
                    rotationY = sayfaSapmasi * -30f
                    scaleX = 1f - (sayfaSapmasi.absoluteValue * 0.15f)
                    scaleY = 1f - (sayfaSapmasi.absoluteValue * 0.15f)
                    alpha = 1f - (sayfaSapmasi.absoluteValue * 0.5f)
                },
                shape = RoundedCornerShape(48.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.45f))
            ) {
                GunIcerigi(
                    gunBilgisi = gun,
                    gorevler = haftalikGorevler[gun.gunAdi] ?: emptyList(),
                    yeniGorevEklendi = { text ->
                        val liste = (haftalikGorevler[gun.gunAdi] ?: emptyList()) + Gorev(text)
                        haftalikGorevler = haftalikGorevler + (gun.gunAdi to liste)
                    },
                    gorevGuncellendi = { idx, text ->
                        val liste = (haftalikGorevler[gun.gunAdi] ?: emptyList()).toMutableList()
                        liste[idx] = liste[idx].copy(metin = text)
                        haftalikGorevler = haftalikGorevler + (gun.gunAdi to liste)
                    },
                    gorevSilindi = { idx ->
                        val liste = (haftalikGorevler[gun.gunAdi] ?: emptyList()).toMutableList()
                        liste.removeAt(idx)
                        haftalikGorevler = haftalikGorevler + (gun.gunAdi to liste)
                    },
                    gorevDurumuDegisti = { idx ->
                        val liste = (haftalikGorevler[gun.gunAdi] ?: emptyList()).toMutableList()
                        liste[idx] = liste[idx].copy(tamamlandi = !liste[idx].tamamlandi)
                        haftalikGorevler = haftalikGorevler + (gun.gunAdi to liste)
                    }
                )
            }
        }

        if (uyariGoster) {
            AlertDialog(
                onDismissRequest = { uyariGoster = false },
                title = { Text("Tümünü Sıfırla?", fontWeight = FontWeight.Bold) },
                text = { Text("Tüm planlar silinecek.") },
                confirmButton = { TextButton(onClick = { haftalikGorevler = emptyMap(); uyariGoster = false }) { Text("Sıfırla", color = Color.Red) } },
                dismissButton = { TextButton(onClick = { uyariGoster = false }) { Text("İptal") } },
                containerColor = Color(0xFF162A45), titleContentColor = Color.White, textContentColor = Color.LightGray
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GunIcerigi(
    gunBilgisi: GunBilgisi,
    gorevler: List<Gorev>,
    yeniGorevEklendi: (String) -> Unit,
    gorevGuncellendi: (Int, String) -> Unit,
    gorevSilindi: (Int) -> Unit,
    gorevDurumuDegisti: (Int) -> Unit
) {
    var yazilanGorev by remember { mutableStateOf("") }
    var duzenlenecekIdx by remember { mutableStateOf<Int?>(null) }
    var duzenlenenMetin by remember { mutableStateOf("") }

    var makaleBaslik by remember { mutableStateOf("Yükleniyor...") }
    var makaleOzet by remember { mutableStateOf("") }
    var makaleUrl by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(gunBilgisi) {
        if (gunBilgisi.gelecekteMi) {
            makaleBaslik = "Günün makalesi henüz yayınlanmadı"
            makaleOzet = "Wikipedia gelecekteki günler için makale sunmaz."
        } else {
            val sonuc = wikipediaMakaleGetir(gunBilgisi.yil, gunBilgisi.ay, gunBilgisi.gun)
            if (sonuc != null) {
                makaleBaslik = sonuc.first; makaleOzet = sonuc.second; makaleUrl = sonuc.third
            } else {
                makaleBaslik = "Bilgi Bulunamadı"; makaleOzet = "Wikipedia'dan veri çekilemedi."
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(gunBilgisi.gunAdi, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(gunBilgisi.ekranTarihi, color = Color.Gray, fontSize = 14.sp)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = yazilanGorev, onValueChange = { yazilanGorev = it },
                modifier = Modifier.weight(1f), placeholder = { Text("Görev...") },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color.Black.copy(0.3f), unfocusedContainerColor = Color.Black.copy(0.3f)),
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (yazilanGorev.isNotBlank()) { yeniGorevEklendi(yazilanGorev); yazilanGorev = "" } }, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("+") }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(gorevler) { idx, gorev ->
                val alpha by animateFloatAsState(if (gorev.tamamlandi) 0.4f else 1f)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        "• ${gorev.metin}", color = Color.White.copy(alpha), fontSize = 16.sp,
                        textDecoration = if (gorev.tamamlandi) TextDecoration.LineThrough else TextDecoration.None,
                        modifier = Modifier.weight(1f).clickable { gorevDurumuDegisti(idx) }.padding(8.dp)
                    )
                    IconButton(onClick = { duzenlenecekIdx = idx; duzenlenenMetin = gorev.metin }) {
                        Icon(Icons.Default.Edit, null, tint = Color.Gray.copy(alpha))
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().clickable { if (makaleUrl.isNotEmpty()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(makaleUrl))) },
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(0.1f)), shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row { Icon(Icons.Default.Info, null, tint = Color.Gray, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Günün Makalesi", fontSize = 12.sp, color = Color.Gray) }
                Text(makaleBaslik, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(makaleOzet, color = Color.LightGray, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontStyle = FontStyle.Italic)
            }
        }
    }

    if (duzenlenecekIdx != null) {
        AlertDialog(
            onDismissRequest = { duzenlenecekIdx = null },
            title = { Text("Düzenle") },
            text = { OutlinedTextField(duzenlenenMetin, { duzenlenenMetin = it }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)) },
            confirmButton = { TextButton(onClick = { if (duzenlenenMetin.isNotBlank()) gorevGuncellendi(duzenlenecekIdx!!, duzenlenenMetin); duzenlenecekIdx = null }) { Text("Kaydet") } },
            dismissButton = { TextButton(onClick = { gorevSilindi(duzenlenecekIdx!!); duzenlenecekIdx = null }) { Text("Sil", color = Color.Red) } },
            containerColor = Color(0xFF162A45), titleContentColor = Color.White
        )
    }
}

@Composable
fun MatrixArkaPlan() {
    val semboller = listOf("∑", "{ }", "</>", "0", "1", "∞", "∫", "π", "val", "fun")
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
        repeat(6) { i -> KayanSutun(semboller.shuffled(), i * 2000) }
    }
}

@Composable
fun KayanSutun(semboller: List<String>, gecikme: Int) {
    val transition = rememberInfiniteTransition()
    val y by transition.animateFloat(initialValue = -1000f, targetValue = 2500f, animationSpec = infiniteRepeatable(tween(15000 + gecikme, easing = LinearEasing)))
    Column(modifier = Modifier.graphicsLayer { translationY = y }) {
        semboller.forEach { Text(it, color = Color.White.copy(0.06f), fontSize = 20.sp, modifier = Modifier.padding(vertical = 30.dp)) }
    }
}
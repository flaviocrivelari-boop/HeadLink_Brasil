package com.starlink.field

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.spacex.api.device.*
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var dadosDish: JSONObject? = null
    private var statusData: DishGetStatusResponse? = null
    private var historyData: GetHistoryResponse? = null
    private var wifiData: WifiGetStatusResponse? = null

    private lateinit var etCliente: EditText
    private lateinit var etLocal: EditText
    private lateinit var etObs: EditText
    private lateinit var btnLer: Button
    private lateinit var btnCompartilhar: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var cardResultado: LinearLayout
    private lateinit var tvResultado: TextView
    private lateinit var tvAlertas: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = Color.parseColor("#0a0f1e")
        etCliente       = findViewById(R.id.etCliente)
        etLocal         = findViewById(R.id.etLocal)
        etObs           = findViewById(R.id.etObs)
        btnLer          = findViewById(R.id.btnLer)
        btnCompartilhar = findViewById(R.id.btnCompartilhar)
        progressBar     = findViewById(R.id.progressBar)
        tvStatus        = findViewById(R.id.tvStatus)
        cardResultado   = findViewById(R.id.cardResultado)
        tvResultado     = findViewById(R.id.tvResultado)
        tvAlertas       = findViewById(R.id.tvAlertas)
        btnLer.setOnClickListener { lerDados() }
        btnCompartilhar.setOnClickListener { compartilharWhatsApp() }
    }

    private fun httpGet(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000; conn.readTimeout = 6000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == 200) {
                val br = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder(); var line: String?
                while (br.readLine().also { line = it } != null) sb.append(line)
                br.close(); conn.disconnect(); sb.toString()
            } else { conn.disconnect(); null }
        } catch (e: Exception) { null }
    }

    private fun formatUptime(s: Long): String {
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "${h}h ${m}m ${sec}s" else "${m}m ${sec}s"
    }

    private fun lerDados() {
        setLoading(true)
        dadosDish = null; statusData = null; historyData = null; wifiData = null

        lifecycleScope.launch {
            // ETAPA 1: HTTP
            tvStatus.text = "1/2 - Lendo identificacao (HTTP)..."
            tvStatus.setTextColor(Color.parseColor("#94a3b8"))
            val jsonStr = withContext(Dispatchers.IO) { httpGet("http://192.168.100.1/") }
            if (jsonStr != null) try { dadosDish = JSONObject(jsonStr) } catch (e: Exception) {}

            // ETAPA 2: gRPC - dish + router
            tvStatus.text = "2/2 - Lendo telemetria (gRPC)..."
            for (porta in listOf(9201, 9200)) {
                if (statusData != null) break
                try {
                    val channel = withContext(Dispatchers.IO) {
                        ManagedChannelBuilder.forAddress("192.168.100.1", porta)
                            .usePlaintext().build()
                    }
                    val stub = withContext(Dispatchers.IO) {
                        DeviceGrpc.newBlockingStub(channel).withDeadlineAfter(8, TimeUnit.SECONDS)
                    }
                    // Status do dish
                    try {
                        statusData = withContext(Dispatchers.IO) {
                            stub.handle(Request.newBuilder()
                                .setGetStatus(GetStatusRequest.getDefaultInstance()).build()
                            ).dishGetStatus
                        }
                    } catch (e: Exception) {}
                    // Historico
                    try {
                        historyData = withContext(Dispatchers.IO) {
                            stub.handle(Request.newBuilder()
                                .setGetHistory(GetHistoryRequest.getDefaultInstance()).build()
                            ).getHistory
                        }
                    } catch (e: Exception) {}
                    // Status do router (target_id = "router")
                    try {
                        wifiData = withContext(Dispatchers.IO) {
                            val stubRouter = DeviceGrpc.newBlockingStub(channel)
                                .withDeadlineAfter(5, TimeUnit.SECONDS)
                            stubRouter.handle(Request.newBuilder()
                                .setTargetId("router")
                                .setGetStatus(GetStatusRequest.getDefaultInstance()).build()
                            ).wifiGetStatus
                        }
                    } catch (e: Exception) {}
                    withContext(Dispatchers.IO) {
                        channel.shutdown().awaitTermination(2, TimeUnit.SECONDS)
                    }
                } catch (e: Exception) {}
            }

            if (dadosDish != null || statusData != null) {
                exibirDados()
            } else {
                setLoading(false)
                tvStatus.text = "Erro: verifique o Wi-Fi da Starlink"
                tvStatus.setTextColor(Color.parseColor("#ef4444"))
                Toast.makeText(this@MainActivity, "Conecte ao Wi-Fi da Starlink", Toast.LENGTH_LONG).show()
            }
        }
    }

    private data class Medias(val dl: Float, val ul: Float, val ping: Float, val drop: Float, val ok: Boolean)

    private fun calcMedias(): Medias {
        val h = historyData?.dish ?: return Medias(0f,0f,0f,0f,false)
        val n = minOf(900, h.popPingLatencyMsCount)
        if (n == 0) return Medias(0f,0f,0f,0f,false)
        var sp=0.0; var sd=0.0; var su=0.0; var sdr=0.0
        for (i in 0 until n) { sp+=h.getPopPingLatencyMs(i); sd+=h.getDownlinkThroughputBps(i); su+=h.getUplinkThroughputBps(i); sdr+=h.getPopPingDropRate(i) }
        return Medias((sd/n/1e6).toFloat(),(su/n/1e6).toFloat(),(sp/n).toFloat(),(sdr/n*100).toFloat(),true)
    }

    private fun buildRelatorio(cli: String, loc: String, obs: String, paraMensagem: Boolean): String {
        val d  = dadosDish
        val g  = statusData
        val w  = wifiData
        val m  = calcMedias()
        val agora = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt","BR")).format(Date())

        // HTTP fields
        val id      = d?.optString("id","---")?.ifBlank{"---"} ?: g?.deviceInfo?.id?.ifBlank{"---"} ?: "---"
        val hwVer   = d?.optString("hardwareVersion","---") ?: g?.deviceInfo?.hardwareVersion?.ifBlank{"---"} ?: "---"
        val swVer   = d?.optString("softwareVersion","---") ?: g?.softwareVersion?.ifBlank{"---"} ?: "---"
        val stowed  = d?.optBoolean("stowed",false) ?: false
        val disable = d?.optString("disablementCode","---") ?: "---"
        val align   = d?.optJSONObject("alignmentStats")
        val az      = align?.optString("boresightAzimuthDeg","---") ?: "---"
        val el      = align?.optString("boresightElevationDeg","---") ?: "---"
        val desAz   = align?.optString("desiredBoresightAzimuthDeg","---") ?: "---"
        val desEl   = align?.optString("desiredBoresightElevationDeg","---") ?: "---"

        // gRPC dish fields
        val temGrpc  = g != null
        val online   = g?.state == DishState.CONNECTED
        val estado   = when (g?.state) {
            DishState.CONNECTED -> "ONLINE"; DishState.SEARCHING -> "BUSCANDO SINAL"
            DishState.BOOTING -> "INICIANDO"; else -> if (temGrpc) "OFFLINE" else "---"
        }
        val dlMbps   = (g?.downlinkThroughputBps ?: 0f) / 1_000_000f
        val ulMbps   = (g?.uplinkThroughputBps ?: 0f) / 1_000_000f
        val latMs    = g?.popPingLatencyMs ?: 0f
        val plPct    = (g?.popPingDropRate ?: 0f) * 100f
        val ethMbps  = g?.ethSpeedMbps ?: 0f
        val obsPct   = (g?.fractionObstructed ?: 0f) * 100f
        val uptimeS  = g?.uptimeS?.toLong() ?: 0L
        val gpsOk    = g?.gpsValid ?: false
        val gpsSats  = g?.gpsSats ?: 0
        val snrAbove = g?.snrAboveNoiseFloor ?: false
        val snrLow   = g?.snrPersistentlyLow ?: false
        val obstr    = g?.currentlyObstructed ?: false
        val timeOk   = g?.timeValid ?: false
        val patches  = g?.patchesValid ?: 0
        val obsIntv  = g?.obstructionInterval ?: 0f
        val readyScp = g?.readyStates?.scp ?: false
        val mobility = when (g?.mobilityClass) {
            MobilityClass.MOBILE -> "MOBILE"; MobilityClass.NOMADIC -> "NOMADIC"; else -> "STATIONARY"
        }
        val classServ = g?.classOfService?.ifBlank{"---"} ?: "---"
        val swUpdate  = g?.softwareUpdateState?.ifBlank{"---"} ?: "---"
        val svcOk     = g?.isServiceStateOk ?: false
        val cfgSnow   = g?.config?.snowMeltMode ?: false
        val cfgPwr    = g?.config?.powerSaveMode ?: false
        val di        = g?.deviceInfo
        val country   = di?.countryCode?.ifBlank{"---"} ?: "---"
        val utcOff    = if ((di?.utcOffsetS ?: 0) != 0) "UTC${if((di?.utcOffsetS?:0)<0)"" else "+"}${(di?.utcOffsetS?:0)/3600}" else "---"
        val bootCt    = di?.bootCount ?: 0
        val buildDt   = di?.buildDate?.ifBlank{"---"} ?: "---"

        // Router fields
        val routerId  = w?.deviceInfo?.id?.ifBlank{"---"} ?: "---"
        val routerSw  = w?.deviceInfo?.softwareVersion?.ifBlank{"---"} ?: "---"
        val routerPing = w?.popPingLatencyMs ?: 0f
        val internet  = w?.internet ?: false
        val ipv6      = w?.ipv6 ?: false

        // Alertas
        val alertas = mutableListOf<String>()
        val ah = d?.optJSONObject("alerts")
        if (ah != null) {
            if (ah.optBoolean("motorsStuck"))                alertas.add("Motor preso")
            if (ah.optBoolean("dishThermalThrottle"))        alertas.add("Thermal throttle")
            if (ah.optBoolean("dishThermalShutdown"))        alertas.add("Shutdown termico")
            if (ah.optBoolean("powerSupplyThermalThrottle")) alertas.add("Fonte superaquecida")
            if (ah.optBoolean("mastNotNearVertical"))        alertas.add("Antena inclinada")
            if (ah.optBoolean("slowEthernetSpeeds"))         alertas.add("Ethernet lento")
            if (ah.optBoolean("softwareInstallPending"))     alertas.add("Atualizacao pendente")
            if (ah.optBoolean("obstructed"))                 alertas.add("Dish obstruido")
            if (ah.optBoolean("dishIsHeating"))              alertas.add("Aquecendo (snow melt)")
            if (ah.optBoolean("movingTooFastForPolicy"))     alertas.add("Velocidade excessiva")
        } else if (g != null) {
            if (g.alerts.motorsStuck)                alertas.add("Motor preso")
            if (g.alerts.thermalThrottle)             alertas.add("Thermal throttle")
            if (g.alerts.dishThermalShutdown)         alertas.add("Shutdown termico")
            if (g.alerts.powerSupplyThermalThrottle)  alertas.add("Fonte superaquecida")
            if (g.alerts.mastNotNearVertical)         alertas.add("Antena inclinada")
            if (g.alerts.slowEthernetSpeeds)          alertas.add("Ethernet lento")
            if (g.alerts.softwareInstallPending)      alertas.add("Atualizacao pendente")
            if (g.alerts.diskObstructed)              alertas.add("Dish obstruido")
            if (g.alerts.dishIsHeating)               alertas.add("Aquecendo (snow melt)")
            if (g.alerts.movingTooFastForPolicy)      alertas.add("Velocidade excessiva")
        }

        val b = if (paraMensagem) "*" else ""
        val sb = StringBuilder()

        if (paraMensagem) {
            sb.appendLine("${b}RELATORIO STARLINK - HeadLink Brasil${b}")
            sb.appendLine("Data: $agora")
            if (cli.isNotBlank()) sb.appendLine("Cliente: $cli")
            if (loc.isNotBlank()) sb.appendLine("Local: $loc")
            sb.appendLine("")
        }

        sb.appendLine("${b}IDENTIFICACAO${b}")
        sb.appendLine("Serial:        $id")
        sb.appendLine("Hardware:      $hwVer")
        sb.appendLine("Firmware:      $swVer")
        if (country != "---") sb.appendLine("Pais:          $country")
        if (utcOff != "---")  sb.appendLine("Fuso:          $utcOff")
        if (bootCt > 0)       sb.appendLine("Boot count:    $bootCt")
        if (buildDt != "---") sb.appendLine("Build date:    $buildDt")
        sb.appendLine("Status kit:    $disable")
        sb.appendLine("Guardado:      ${if (stowed) "Sim" else "Nao"}")
        if (temGrpc) {
            sb.appendLine("Mobilidade:    $mobility")
            sb.appendLine("Servico:       $classServ")
            sb.appendLine("Svc state:     ${if (svcOk) "OKAY" else "PROBLEMA"}")
            if (swUpdate.isNotBlank() && swUpdate != "---") sb.appendLine("SW update:     $swUpdate")
            sb.appendLine("Uptime:        ${formatUptime(uptimeS)}")
        }
        sb.appendLine("")

        sb.appendLine("${b}CONECTIVIDADE${b}")
        sb.appendLine("Status:        $estado")
        if (temGrpc) {
            sb.appendLine("Download:      ${"%.3f".format(dlMbps)} Mbps")
            sb.appendLine("Upload:        ${"%.3f".format(ulMbps)} Mbps")
            sb.appendLine("Latencia:      ${"%.2f".format(latMs)} ms")
            sb.appendLine("Packet Loss:   ${"%.2f".format(plPct)}%")
            sb.appendLine("Ethernet:      ${"%.0f".format(ethMbps)} Mbps")
        }
        if (m.ok) {
            sb.appendLine("")
            sb.appendLine("${b}MEDIA 15 MINUTOS${b}")
            sb.appendLine("Download:      ${"%.3f".format(m.dl)} Mbps")
            sb.appendLine("Upload:        ${"%.3f".format(m.ul)} Mbps")
            sb.appendLine("Latencia:      ${"%.2f".format(m.ping)} ms")
            sb.appendLine("Pkt Loss:      ${"%.2f".format(m.drop)}%")
        }
        sb.appendLine("")

        sb.appendLine("${b}ANTENA${b}")
        sb.appendLine("Azimute atual: $az graus")
        sb.appendLine("Elevacao atual:$el graus")
        sb.appendLine("Azimute alvo:  $desAz graus")
        sb.appendLine("Elevacao alvo: $desEl graus")
        if (temGrpc) {
            sb.appendLine("Obstrucao:     ${"%.1f".format(obsPct)}%")
            sb.appendLine("Obstruido:     ${if (obstr) "SIM" else "Nao"}")
            sb.appendLine("SNR ok:        ${if (snrAbove) "Sim" else "Nao"}")
            sb.appendLine("SNR baixo:     ${if (snrLow) "Sim" else "Nao"}")
            sb.appendLine("Hora valida:   ${if (timeOk) "Sim" else "Nao"}")
            if (patches > 0) sb.appendLine("Patches:       $patches")
        }
        sb.appendLine("")

        sb.appendLine("${b}GPS${b}")
        if (temGrpc) {
            sb.appendLine("GPS valido:    ${if (gpsOk) "Sim" else "Nao"}")
            sb.appendLine("Satelites:     $gpsSats")
        } else sb.appendLine("(requer gRPC)")
        sb.appendLine("")

        sb.appendLine("${b}ROUTER${b}")
        if (w != null) {
            sb.appendLine("Router ID:     $routerId")
            sb.appendLine("Router FW:     $routerSw")
            sb.appendLine("Router ping:   ${"%.2f".format(routerPing)} ms")
            sb.appendLine("Internet:      ${if (internet) "Sim" else "Nao"}")
            sb.appendLine("IPv6:          ${if (ipv6) "Sim" else "Nao"}")
        } else sb.appendLine("(requer gRPC)")
        sb.appendLine("")

        sb.appendLine("${b}CONFIG${b}")
        if (temGrpc) {
            sb.appendLine("Snow melt:     ${if (cfgSnow) "Ativo" else "Inativo"}")
            sb.appendLine("Power save:    ${if (cfgPwr) "Ativo" else "Inativo"}")
            sb.appendLine("Ready (SCP):   ${if (readyScp) "Sim" else "Nao"}")
        } else sb.appendLine("(requer gRPC)")
        sb.appendLine("")

        sb.appendLine("${b}ALERTAS${b}")
        if (alertas.isEmpty()) sb.appendLine("Nenhum alerta ativo")
        else for (a in alertas) sb.appendLine("- $a")

        if (paraMensagem) {
            if (obs.isNotBlank()) { sb.appendLine(""); sb.appendLine("Obs: $obs") }
            sb.appendLine("")
            sb.append("_HeadLink Brasil - Todos os direitos reservados_")
        }

        return sb.toString()
    }

    private fun exibirDados() {
        setLoading(false)
        val g = statusData
        val id = dadosDish?.optString("id","---") ?: g?.deviceInfo?.id?.ifBlank{"---"} ?: "---"
        tvStatus.text = "Serial: $id | ${if (g != null) "gRPC OK" else "somente HTTP"}"
        tvStatus.setTextColor(if (id != "---") Color.parseColor("#22c55e") else Color.parseColor("#f59e0b"))

        val g2 = statusData
        val alertas = mutableListOf<String>()
        val ah = dadosDish?.optJSONObject("alerts")
        if (ah != null) {
            if (ah.optBoolean("motorsStuck"))                alertas.add("Motor preso")
            if (ah.optBoolean("dishThermalThrottle"))        alertas.add("Thermal throttle")
            if (ah.optBoolean("dishThermalShutdown"))        alertas.add("Shutdown termico")
            if (ah.optBoolean("powerSupplyThermalThrottle")) alertas.add("Fonte superaquecida")
            if (ah.optBoolean("mastNotNearVertical"))        alertas.add("Antena inclinada")
            if (ah.optBoolean("slowEthernetSpeeds"))         alertas.add("Ethernet lento")
            if (ah.optBoolean("softwareInstallPending"))     alertas.add("Atualizacao pendente")
            if (ah.optBoolean("obstructed"))                 alertas.add("Dish obstruido")
            if (ah.optBoolean("dishIsHeating"))              alertas.add("Aquecendo (snow melt)")
        } else if (g2 != null) {
            if (g2.alerts.motorsStuck)               alertas.add("Motor preso")
            if (g2.alerts.thermalThrottle)            alertas.add("Thermal throttle")
            if (g2.alerts.softwareInstallPending)     alertas.add("Atualizacao pendente")
        }

        tvResultado.text = buildRelatorio("","","",false)
        tvAlertas.text = if (alertas.isEmpty()) "Nenhum alerta ativo"
                         else alertas.joinToString("\n") { a -> "- $a" }
        tvAlertas.setTextColor(if (alertas.isEmpty()) Color.parseColor("#22c55e") else Color.parseColor("#ef4444"))
        cardResultado.visibility   = View.VISIBLE
        btnCompartilhar.visibility = View.VISIBLE
    }

    private fun compartilharWhatsApp() {
        val cli = etCliente.text.toString().trim()
        val loc = etLocal.text.toString().trim()
        val obs = etObs.text.toString().trim()
        val msg = buildRelatorio(cli, loc, obs, true)
        try {
            startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, msg)
            })
        } catch (e: Exception) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, msg)
            }, "Compartilhar"))
        }
    }

    private fun setLoading(on: Boolean) {
        progressBar.visibility = if (on) View.VISIBLE else View.GONE
        btnLer.isEnabled = !on; btnLer.alpha = if (on) 0.5f else 1.0f
        if (on) { cardResultado.visibility = View.GONE; btnCompartilhar.visibility = View.GONE }
    }
}

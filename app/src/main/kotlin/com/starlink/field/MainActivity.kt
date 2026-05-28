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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private var statusData: DishGetStatusResponse? = null
    private var deviceInfoData: GetDeviceInfoResponse? = null
    private var historyData: GetHistoryResponse? = null

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

    private fun lerDados() {
        setLoading(true)
        tvStatus.text = "Conectando em 192.168.100.1:9200..."
        tvStatus.setTextColor(Color.parseColor("#94a3b8"))

        lifecycleScope.launch {
            try {
                val channel = withContext(Dispatchers.IO) {
                    ManagedChannelBuilder
                        .forAddress("192.168.100.1", 9200)
                        .usePlaintext()
                        .build()
                }

                val stub = withContext(Dispatchers.IO) {
                    DeviceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                }

                // Coleta status principal
                statusData = withContext(Dispatchers.IO) {
                    stub.handle(Request.newBuilder()
                        .setGetStatus(GetStatusRequest.getDefaultInstance())
                        .build()).dishGetStatus
                }

                // Coleta device info (serial, hardware version, etc)
                try {
                    deviceInfoData = withContext(Dispatchers.IO) {
                        stub.handle(Request.newBuilder()
                            .setGetDeviceInfo(GetDeviceInfoRequest.getDefaultInstance())
                            .build()).getDeviceInfo
                    }
                } catch (e: Exception) { /* opcional */ }

                // Coleta historico (metricas dos ultimos 15 min)
                try {
                    historyData = withContext(Dispatchers.IO) {
                        stub.handle(Request.newBuilder()
                            .setGetHistory(GetHistoryRequest.getDefaultInstance())
                            .build()).getHistory
                    }
                } catch (e: Exception) { /* opcional */ }

                withContext(Dispatchers.IO) {
                    channel.shutdown().awaitTermination(2, TimeUnit.SECONDS)
                }

                exibirDados()

            } catch (e: Exception) {
                setLoading(false)
                tvStatus.text = "Erro: ${e.message?.take(100)}"
                tvStatus.setTextColor(Color.parseColor("#ef4444"))
                Toast.makeText(this@MainActivity,
                    "Verifique se esta no Wi-Fi da Starlink", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exibirDados() {
        val d = statusData ?: return
        setLoading(false)

        val online  = d.state == DishState.CONNECTED
        val dlMbps  = d.downlinkThroughputBps / 1_000_000f
        val ulMbps  = d.uplinkThroughputBps / 1_000_000f
        val plPct   = d.popPingDropRate * 100f
        val obsPct  = d.fractionObstructed * 100f
        val uptimeH = d.uptimeS / 3600f

        // Info do device (serial, hw version)
        val di = deviceInfoData?.deviceInfo ?: d.deviceInfo
        val serial  = di?.serialNumber?.ifBlank { "---" } ?: "---"
        val hwVer   = di?.hardwareVersion?.ifBlank { "---" } ?: "---"
        val swVer   = d.softwareVersion.ifBlank { di?.softwareVersion?.ifBlank { "---" } ?: "---" }

        // Media do historico
        var avgPing = 0f; var avgDl = 0f; var avgUl = 0f; var avgDrop = 0f
        historyData?.dish?.let { h ->
            val n = minOf(900, h.popPingLatencyMsCount)
            if (n > 0) {
                avgPing = (0 until n).map { h.getPopPingLatencyMs(it) }.average().toFloat()
                avgDl   = (0 until n).map { h.getDownlinkThroughputBps(it) }.average().toFloat() / 1_000_000f
                avgUl   = (0 until n).map { h.getUplinkThroughputBps(it) }.average().toFloat() / 1_000_000f
                avgDrop = (0 until n).map { h.getPopPingDropRate(it) }.average().toFloat() * 100f
            }
        }

        tvStatus.text = if (online) "Leitura concluida com sucesso!" else "Dish offline ou buscando sinal"
        tvStatus.setTextColor(if (online) Color.parseColor("#22c55e") else Color.parseColor("#f59e0b"))

        tvResultado.text = buildString {
            appendLine("=== IDENTIFICACAO ===")
            appendLine("Serial Number: $serial")
            appendLine("Hardware:      $hwVer")
            appendLine("Firmware:      $swVer")
            appendLine("")
            appendLine("=== CONECTIVIDADE ATUAL ===")
            appendLine("Status:        ${if (online) "ONLINE" else "OFFLINE"}")
            appendLine("Download:      ${"%.1f".format(dlMbps)} Mbps")
            appendLine("Upload:        ${"%.1f".format(ulMbps)} Mbps")
            appendLine("Latencia:      ${"%.0f".format(d.popPingLatencyMs)} ms")
            appendLine("Packet Loss:   ${"%.2f".format(plPct)}%")
            appendLine("SNR:           ${"%.1f".format(d.snr)} dB")
            appendLine("")
            appendLine("=== MEDIA 15 MINUTOS ===")
            if (avgPing > 0f) {
                appendLine("Download med:  ${"%.1f".format(avgDl)} Mbps")
                appendLine("Upload med:    ${"%.1f".format(avgUl)} Mbps")
                appendLine("Latencia med:  ${"%.0f".format(avgPing)} ms")
                appendLine("Pkt Loss med:  ${"%.2f".format(avgDrop)}%")
            } else {
                appendLine("(historico nao disponivel)")
            }
            appendLine("")
            appendLine("=== ANTENA ===")
            appendLine("Obstrucao:     ${"%.1f".format(obsPct)}%")
            appendLine("Obstr.atual:   ${if (d.currentlyObstructed) "SIM" else "Nao"}")
            appendLine("Azimute:       ${"%.1f".format(d.directionAzimuth)} graus")
            appendLine("Elevacao:      ${"%.1f".format(d.directionElevation)} graus")
            appendLine("Uptime:        ${"%.1f".format(uptimeH)} h")
            appendLine("")
            appendLine("=== GPS ===")
            appendLine("GPS valido:    ${if (d.gpsValid) "Sim" else "Nao"}")
            append("Satelites GPS: ${d.gpsSats}")
        }

        val alertas = mutableListOf<String>()
        if (d.alerts.motorsStuck)               alertas += "Motor preso"
        if (d.alerts.thermalThrottle)            alertas += "Thermal throttle"
        if (d.alerts.mastNotNearVertical)        alertas += "Antena inclinada"
        if (d.alerts.diskObstructed)             alertas += "Dish obstruido"
        if (d.alerts.slowEthernetSpeeds)         alertas += "Ethernet lento"
        if (d.alerts.softwareInstallPending)     alertas += "Atualizacao pendente"
        if (d.alerts.movingWhileNotMobile)       alertas += "Movendo sem modo mobile"
        if (d.alerts.powerSupplyThermalThrottle) alertas += "Fonte superaquecida"
        if (d.alerts.roamingNoService)           alertas += "Roaming sem servico"

        tvAlertas.text = if (alertas.isEmpty()) "Nenhum alerta ativo"
                         else alertas.joinToString("\n") { "• $_it" }
        tvAlertas.setTextColor(if (alertas.isEmpty()) Color.parseColor("#22c55e")
                               else Color.parseColor("#ef4444"))

        cardResultado.visibility = View.VISIBLE
        btnCompartilhar.visibility = View.VISIBLE
    }

    private fun compartilharWhatsApp() {
        val d = statusData ?: return
        val agora   = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt","BR")).format(Date())
        val online  = d.state == DishState.CONNECTED
        val dlMbps  = d.downlinkThroughputBps / 1_000_000f
        val ulMbps  = d.uplinkThroughputBps / 1_000_000f
        val plPct   = d.popPingDropRate * 100f
        val obsPct  = d.fractionObstructed * 100f
        val uptimeH = d.uptimeS / 3600f
        val cli     = etCliente.text.toString().trim()
        val loc     = etLocal.text.toString().trim()
        val obs     = etObs.text.toString().trim()

        val di     = deviceInfoData?.deviceInfo ?: d.deviceInfo
        val serial = di?.serialNumber?.ifBlank { "---" } ?: "---"
        val hwVer  = di?.hardwareVersion?.ifBlank { "---" } ?: "---"
        val swVer  = d.softwareVersion.ifBlank { "---" }

        var avgPing = 0f; var avgDl = 0f; var avgUl = 0f; var avgDrop = 0f
        historyData?.dish?.let { h ->
            val n = minOf(900, h.popPingLatencyMsCount)
            if (n > 0) {
                avgPing = (0 until n).map { h.getPopPingLatencyMs(it) }.average().toFloat()
                avgDl   = (0 until n).map { h.getDownlinkThroughputBps(it) }.average().toFloat() / 1_000_000f
                avgUl   = (0 until n).map { h.getUplinkThroughputBps(it) }.average().toFloat() / 1_000_000f
                avgDrop = (0 until n).map { h.getPopPingDropRate(it) }.average().toFloat() * 100f
            }
        }

        val alertas = mutableListOf<String>()
        if (d.alerts.motorsStuck)               alertas += "Motor preso"
        if (d.alerts.thermalThrottle)            alertas += "Thermal throttle"
        if (d.alerts.mastNotNearVertical)        alertas += "Antena inclinada"
        if (d.alerts.diskObstructed)             alertas += "Dish obstruido"
        if (d.alerts.slowEthernetSpeeds)         alertas += "Ethernet lento"
        if (d.alerts.softwareInstallPending)     alertas += "Atualizacao pendente"
        if (d.alerts.movingWhileNotMobile)       alertas += "Movendo sem modo mobile"
        if (d.alerts.powerSupplyThermalThrottle) alertas += "Fonte superaquecida"
        if (d.alerts.roamingNoService)           alertas += "Roaming sem servico"

        val msg = buildString {
            appendLine("*RELATORIO STARLINK - HeadLink Brasil*")
            appendLine("Data: $agora")
            if (cli.isNotBlank()) appendLine("Cliente: $cli")
            if (loc.isNotBlank()) appendLine("Local: $loc")
            appendLine("")
            appendLine("*IDENTIFICACAO*")
            appendLine("Serial Number: $serial")
            appendLine("Hardware: $hwVer")
            appendLine("Firmware: $swVer")
            appendLine("")
            appendLine("*CONECTIVIDADE ATUAL*")
            appendLine("Status: ${if (online) "Online" else "Offline"}")
            appendLine("Download: ${"%.1f".format(dlMbps)} Mbps")
            appendLine("Upload: ${"%.1f".format(ulMbps)} Mbps")
            appendLine("Latencia: ${"%.0f".format(d.popPingLatencyMs)} ms")
            appendLine("Packet Loss: ${"%.2f".format(plPct)}%")
            appendLine("SNR: ${"%.1f".format(d.snr)} dB")
            appendLine("")
            if (avgPing > 0f) {
                appendLine("*MEDIA ULTIMOS 15 MINUTOS*")
                appendLine("Download: ${"%.1f".format(avgDl)} Mbps")
                appendLine("Upload: ${"%.1f".format(avgUl)} Mbps")
                appendLine("Latencia: ${"%.0f".format(avgPing)} ms")
                appendLine("Packet Loss: ${"%.2f".format(avgDrop)}%")
                appendLine("")
            }
            appendLine("*ANTENA E GPS*")
            appendLine("Obstrucao: ${"%.1f".format(obsPct)}%")
            appendLine("Obstruido agora: ${if (d.currentlyObstructed) "SIM" else "Nao"}")
            appendLine("Azimute: ${"%.1f".format(d.directionAzimuth)} graus")
            appendLine("Elevacao: ${"%.1f".format(d.directionElevation)} graus")
            appendLine("GPS valido: ${if (d.gpsValid) "Sim" else "Nao"}")
            appendLine("Satelites GPS: ${d.gpsSats}")
            appendLine("Uptime: ${"%.1f".format(uptimeH)} h")
            appendLine("")
            appendLine("*ALERTAS*")
            if (alertas.isEmpty()) appendLine("Nenhum alerta ativo")
            else alertas.forEach { appendLine("• $it") }
            if (obs.isNotBlank()) { appendLine(""); appendLine("Obs: $obs") }
            appendLine("")
            append("_HeadLink Brasil - Todos os direitos reservados_")
        }

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
        progressBar.visibility  = if (on) View.VISIBLE else View.GONE
        btnLer.isEnabled        = !on
        btnLer.alpha            = if (on) 0.5f else 1.0f
        if (on) { cardResultado.visibility = View.GONE; btnCompartilhar.visibility = View.GONE }
    }
}

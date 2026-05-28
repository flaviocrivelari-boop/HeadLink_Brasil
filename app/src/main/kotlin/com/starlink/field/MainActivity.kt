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

                // Status principal
                statusData = withContext(Dispatchers.IO) {
                    val req = Request.newBuilder()
                        .setGetStatus(GetStatusRequest.getDefaultInstance())
                        .build()
                    stub.handle(req).dishGetStatus
                }

                // Device info (serial, hw version)
                try {
                    deviceInfoData = withContext(Dispatchers.IO) {
                        val req = Request.newBuilder()
                            .setGetDeviceInfo(GetDeviceInfoRequest.getDefaultInstance())
                            .build()
                        stub.handle(req).getDeviceInfo
                    }
                } catch (_: Exception) {}

                // Historico 15 min
                try {
                    historyData = withContext(Dispatchers.IO) {
                        val req = Request.newBuilder()
                            .setGetHistory(GetHistoryRequest.getDefaultInstance())
                            .build()
                        stub.handle(req).getHistory
                    }
                } catch (_: Exception) {}

                withContext(Dispatchers.IO) {
                    channel.shutdown().awaitTermination(2, TimeUnit.SECONDS)
                }

                exibirDados()

            } catch (e: Exception) {
                setLoading(false)
                tvStatus.text = "Erro: ${e.message?.take(100)}"
                tvStatus.setTextColor(Color.parseColor("#ef4444"))
                Toast.makeText(
                    this@MainActivity,
                    "Verifique se esta no Wi-Fi da Starlink",
                    Toast.LENGTH_LONG
                ).show()
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

        val di     = if (deviceInfoData != null) deviceInfoData!!.deviceInfo else d.deviceInfo
        val serial = if (di != null && di.serialNumber.isNotBlank()) di.serialNumber else "---"
        val hwVer  = if (di != null && di.hardwareVersion.isNotBlank()) di.hardwareVersion else "---"
        val swVer  = if (d.softwareVersion.isNotBlank()) d.softwareVersion else "---"

        // Calcula medias do historico
        var avgPing = 0f
        var avgDl   = 0f
        var avgUl   = 0f
        var avgDrop = 0f
        var temHistorico = false

        val hist = historyData?.dish
        if (hist != null && hist.popPingLatencyMsCount > 0) {
            val n = minOf(900, hist.popPingLatencyMsCount)
            var sumPing = 0.0; var sumDl = 0.0; var sumUl = 0.0; var sumDrop = 0.0
            for (i in 0 until n) {
                sumPing += hist.getPopPingLatencyMs(i)
                sumDl   += hist.getDownlinkThroughputBps(i)
                sumUl   += hist.getUplinkThroughputBps(i)
                sumDrop += hist.getPopPingDropRate(i)
            }
            avgPing = (sumPing / n).toFloat()
            avgDl   = (sumDl / n / 1_000_000.0).toFloat()
            avgUl   = (sumUl / n / 1_000_000.0).toFloat()
            avgDrop = (sumDrop / n * 100.0).toFloat()
            temHistorico = true
        }

        tvStatus.text = if (online) "Leitura concluida com sucesso!" else "Dish offline ou buscando sinal"
        tvStatus.setTextColor(
            if (online) Color.parseColor("#22c55e") else Color.parseColor("#f59e0b")
        )

        val sb = StringBuilder()
        sb.appendLine("=== IDENTIFICACAO ===")
        sb.appendLine("Serial Number: $serial")
        sb.appendLine("Hardware:      $hwVer")
        sb.appendLine("Firmware:      $swVer")
        sb.appendLine("")
        sb.appendLine("=== CONECTIVIDADE ATUAL ===")
        sb.appendLine("Status:        ${if (online) "ONLINE" else "OFFLINE"}")
        sb.appendLine("Download:      ${"%.1f".format(dlMbps)} Mbps")
        sb.appendLine("Upload:        ${"%.1f".format(ulMbps)} Mbps")
        sb.appendLine("Latencia:      ${"%.0f".format(d.popPingLatencyMs)} ms")
        sb.appendLine("Packet Loss:   ${"%.2f".format(plPct)}%")
        sb.appendLine("SNR:           ${"%.1f".format(d.snr)} dB")
        sb.appendLine("")
        if (temHistorico) {
            sb.appendLine("=== MEDIA 15 MINUTOS ===")
            sb.appendLine("Download med:  ${"%.1f".format(avgDl)} Mbps")
            sb.appendLine("Upload med:    ${"%.1f".format(avgUl)} Mbps")
            sb.appendLine("Latencia med:  ${"%.0f".format(avgPing)} ms")
            sb.appendLine("Pkt Loss med:  ${"%.2f".format(avgDrop)}%")
            sb.appendLine("")
        }
        sb.appendLine("=== ANTENA ===")
        sb.appendLine("Obstrucao:     ${"%.1f".format(obsPct)}%")
        sb.appendLine("Obstr.atual:   ${if (d.currentlyObstructed) "SIM" else "Nao"}")
        sb.appendLine("Azimute:       ${"%.1f".format(d.directionAzimuth)} graus")
        sb.appendLine("Elevacao:      ${"%.1f".format(d.directionElevation)} graus")
        sb.appendLine("Uptime:        ${"%.1f".format(uptimeH)} h")
        sb.appendLine("")
        sb.appendLine("=== GPS ===")
        sb.appendLine("GPS valido:    ${if (d.gpsValid) "Sim" else "Nao"}")
        sb.append("Satelites GPS: ${d.gpsSats}")

        tvResultado.text = sb.toString()

        val alertas = mutableListOf<String>()
        if (d.alerts.motorsStuck)               alertas.add("Motor preso")
        if (d.alerts.thermalThrottle)            alertas.add("Thermal throttle")
        if (d.alerts.mastNotNearVertical)        alertas.add("Antena inclinada")
        if (d.alerts.diskObstructed)             alertas.add("Dish obstruido")
        if (d.alerts.slowEthernetSpeeds)         alertas.add("Ethernet lento")
        if (d.alerts.softwareInstallPending)     alertas.add("Atualizacao pendente")
        if (d.alerts.movingWhileNotMobile)       alertas.add("Movendo sem modo mobile")
        if (d.alerts.powerSupplyThermalThrottle) alertas.add("Fonte superaquecida")
        if (d.alerts.roamingNoService)           alertas.add("Roaming sem servico")

        tvAlertas.text = if (alertas.isEmpty()) "Nenhum alerta ativo"
                         else alertas.joinToString("\n") { a -> "- $a" }
        tvAlertas.setTextColor(
            if (alertas.isEmpty()) Color.parseColor("#22c55e") else Color.parseColor("#ef4444")
        )

        cardResultado.visibility   = View.VISIBLE
        btnCompartilhar.visibility = View.VISIBLE
    }

    private fun compartilharWhatsApp() {
        val d = statusData ?: return
        val agora   = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())
        val online  = d.state == DishState.CONNECTED
        val dlMbps  = d.downlinkThroughputBps / 1_000_000f
        val ulMbps  = d.uplinkThroughputBps / 1_000_000f
        val plPct   = d.popPingDropRate * 100f
        val obsPct  = d.fractionObstructed * 100f
        val uptimeH = d.uptimeS / 3600f
        val cli     = etCliente.text.toString().trim()
        val loc     = etLocal.text.toString().trim()
        val obs     = etObs.text.toString().trim()

        val di     = if (deviceInfoData != null) deviceInfoData!!.deviceInfo else d.deviceInfo
        val serial = if (di != null && di.serialNumber.isNotBlank()) di.serialNumber else "---"
        val hwVer  = if (di != null && di.hardwareVersion.isNotBlank()) di.hardwareVersion else "---"
        val swVer  = if (d.softwareVersion.isNotBlank()) d.softwareVersion else "---"

        var avgPing = 0f; var avgDl = 0f; var avgUl = 0f; var avgDrop = 0f
        var temHistorico = false
        val hist = historyData?.dish
        if (hist != null && hist.popPingLatencyMsCount > 0) {
            val n = minOf(900, hist.popPingLatencyMsCount)
            var sumPing = 0.0; var sumDl = 0.0; var sumUl = 0.0; var sumDrop = 0.0
            for (i in 0 until n) {
                sumPing += hist.getPopPingLatencyMs(i)
                sumDl   += hist.getDownlinkThroughputBps(i)
                sumUl   += hist.getUplinkThroughputBps(i)
                sumDrop += hist.getPopPingDropRate(i)
            }
            avgPing = (sumPing / n).toFloat()
            avgDl   = (sumDl / n / 1_000_000.0).toFloat()
            avgUl   = (sumUl / n / 1_000_000.0).toFloat()
            avgDrop = (sumDrop / n * 100.0).toFloat()
            temHistorico = true
        }

        val alertas = mutableListOf<String>()
        if (d.alerts.motorsStuck)               alertas.add("Motor preso")
        if (d.alerts.thermalThrottle)            alertas.add("Thermal throttle")
        if (d.alerts.mastNotNearVertical)        alertas.add("Antena inclinada")
        if (d.alerts.diskObstructed)             alertas.add("Dish obstruido")
        if (d.alerts.slowEthernetSpeeds)         alertas.add("Ethernet lento")
        if (d.alerts.softwareInstallPending)     alertas.add("Atualizacao pendente")
        if (d.alerts.movingWhileNotMobile)       alertas.add("Movendo sem modo mobile")
        if (d.alerts.powerSupplyThermalThrottle) alertas.add("Fonte superaquecida")
        if (d.alerts.roamingNoService)           alertas.add("Roaming sem servico")

        val sb = StringBuilder()
        sb.appendLine("*RELATORIO STARLINK - HeadLink Brasil*")
        sb.appendLine("Data: $agora")
        if (cli.isNotBlank()) sb.appendLine("Cliente: $cli")
        if (loc.isNotBlank()) sb.appendLine("Local: $loc")
        sb.appendLine("")
        sb.appendLine("*IDENTIFICACAO*")
        sb.appendLine("Serial Number: $serial")
        sb.appendLine("Hardware: $hwVer")
        sb.appendLine("Firmware: $swVer")
        sb.appendLine("")
        sb.appendLine("*CONECTIVIDADE ATUAL*")
        sb.appendLine("Status: ${if (online) "Online" else "Offline"}")
        sb.appendLine("Download: ${"%.1f".format(dlMbps)} Mbps")
        sb.appendLine("Upload: ${"%.1f".format(ulMbps)} Mbps")
        sb.appendLine("Latencia: ${"%.0f".format(d.popPingLatencyMs)} ms")
        sb.appendLine("Packet Loss: ${"%.2f".format(plPct)}%")
        sb.appendLine("SNR: ${"%.1f".format(d.snr)} dB")
        sb.appendLine("")
        if (temHistorico) {
            sb.appendLine("*MEDIA ULTIMOS 15 MINUTOS*")
            sb.appendLine("Download: ${"%.1f".format(avgDl)} Mbps")
            sb.appendLine("Upload: ${"%.1f".format(avgUl)} Mbps")
            sb.appendLine("Latencia: ${"%.0f".format(avgPing)} ms")
            sb.appendLine("Packet Loss: ${"%.2f".format(avgDrop)}%")
            sb.appendLine("")
        }
        sb.appendLine("*ANTENA E GPS*")
        sb.appendLine("Obstrucao: ${"%.1f".format(obsPct)}%")
        sb.appendLine("Obstruido agora: ${if (d.currentlyObstructed) "SIM" else "Nao"}")
        sb.appendLine("Azimute: ${"%.1f".format(d.directionAzimuth)} graus")
        sb.appendLine("Elevacao: ${"%.1f".format(d.directionElevation)} graus")
        sb.appendLine("GPS valido: ${if (d.gpsValid) "Sim" else "Nao"}")
        sb.appendLine("Satelites GPS: ${d.gpsSats}")
        sb.appendLine("Uptime: ${"%.1f".format(uptimeH)} h")
        sb.appendLine("")
        sb.appendLine("*ALERTAS*")
        if (alertas.isEmpty()) {
            sb.appendLine("Nenhum alerta ativo")
        } else {
            for (a in alertas) sb.appendLine("- $a")
        }
        if (obs.isNotBlank()) {
            sb.appendLine("")
            sb.appendLine("Obs: $obs")
        }
        sb.appendLine("")
        sb.append("_HeadLink Brasil - Todos os direitos reservados_")

        val msg = sb.toString()
        try {
            startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, msg)
            })
        } catch (e: Exception) {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, msg)
                }, "Compartilhar"
            ))
        }
    }

    private fun setLoading(on: Boolean) {
        progressBar.visibility  = if (on) View.VISIBLE else View.GONE
        btnLer.isEnabled        = !on
        btnLer.alpha            = if (on) 0.5f else 1.0f
        if (on) {
            cardResultado.visibility   = View.GONE
            btnCompartilhar.visibility = View.GONE
        }
    }
}

package com.starlink.field

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.spacex.api.device.*
import io.grpc.ManagedChannel
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

    private fun criarCanal(porta: Int): ManagedChannel {
        return ManagedChannelBuilder
            .forAddress("192.168.100.1", porta)
            .usePlaintext()
            .build()
    }

    private fun lerDados() {
        setLoading(true)
        tvStatus.text = "Conectando ao dish..."
        tvStatus.setTextColor(Color.parseColor("#94a3b8"))

        lifecycleScope.launch {
            var conectou = false

            // Tenta porta 9201 primeiro (mesma que o app Starlink usa)
            for (porta in listOf(9201, 9200)) {
                if (conectou) break
                try {
                    tvStatus.text = "Tentando porta $porta..."

                    val channel = withContext(Dispatchers.IO) { criarCanal(porta) }
                    val stub = withContext(Dispatchers.IO) {
                        DeviceGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(8, TimeUnit.SECONDS)
                    }

                    // Busca status (contém device_info com serial)
                    try {
                        val resp = withContext(Dispatchers.IO) {
                            stub.handle(
                                Request.newBuilder()
                                    .setGetStatus(GetStatusRequest.getDefaultInstance())
                                    .build()
                            )
                        }
                        statusData = resp.dishGetStatus
                        conectou = true
                    } catch (e: Exception) {
                        // status falhou, tenta device info separado
                    }

                    // Busca device info separado (serial mesmo offline)
                    try {
                        val resp = withContext(Dispatchers.IO) {
                            stub.handle(
                                Request.newBuilder()
                                    .setGetDeviceInfo(GetDeviceInfoRequest.getDefaultInstance())
                                    .build()
                            )
                        }
                        deviceInfoData = resp.getDeviceInfo
                        conectou = true
                    } catch (e: Exception) { }

                    // Busca historico (so online)
                    try {
                        val resp = withContext(Dispatchers.IO) {
                            stub.handle(
                                Request.newBuilder()
                                    .setGetHistory(GetHistoryRequest.getDefaultInstance())
                                    .build()
                            )
                        }
                        historyData = resp.getHistory
                    } catch (e: Exception) { }

                    withContext(Dispatchers.IO) {
                        channel.shutdown().awaitTermination(2, TimeUnit.SECONDS)
                    }

                } catch (e: Exception) {
                    // porta $porta falhou, tenta a proxima
                }
            }

            if (conectou) {
                exibirDados()
            } else {
                setLoading(false)
                tvStatus.text = "Nao foi possivel conectar ao dish"
                tvStatus.setTextColor(Color.parseColor("#ef4444"))
                Toast.makeText(
                    this@MainActivity,
                    "Verifique se o celular esta no Wi-Fi da Starlink",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun resolverSerial(): String {
        // Prioridade: deviceInfoData > statusData.deviceInfo
        val diResp = deviceInfoData?.deviceInfo
        if (diResp != null && diResp.id.isNotBlank()) return diResp.id
        val diStatus = statusData?.deviceInfo
        if (diStatus != null && diStatus.id.isNotBlank()) return diStatus.id
        return "---"
    }

    private fun resolverHardware(): String {
        val diResp = deviceInfoData?.deviceInfo
        if (diResp != null && diResp.hardwareVersion.isNotBlank()) return diResp.hardwareVersion
        val diStatus = statusData?.deviceInfo
        if (diStatus != null && diStatus.hardwareVersion.isNotBlank()) return diStatus.hardwareVersion
        return "---"
    }

    private fun resolverFirmware(): String {
        val sw = statusData?.softwareVersion
        if (!sw.isNullOrBlank()) return sw
        val diResp = deviceInfoData?.deviceInfo
        if (diResp != null && diResp.softwareVersion.isNotBlank()) return diResp.softwareVersion
        return "---"
    }

    private fun exibirDados() {
        setLoading(false)

        val d       = statusData
        val online  = d?.state == DishState.CONNECTED
        val dlMbps  = (d?.downlinkThroughputBps ?: 0f) / 1_000_000f
        val ulMbps  = (d?.uplinkThroughputBps ?: 0f) / 1_000_000f
        val plPct   = (d?.popPingDropRate ?: 0f) * 100f
        val obsPct  = (d?.fractionObstructed ?: 0f) * 100f
        val uptimeH = (d?.uptimeS?.toLong() ?: 0L) / 3600f

        val serial = resolverSerial()
        val hwVer  = resolverHardware()
        val swVer  = resolverFirmware()

        val estadoStr = when (d?.state) {
            DishState.CONNECTED -> "ONLINE"
            DishState.SEARCHING -> "BUSCANDO SINAL"
            DishState.BOOTING   -> "INICIANDO"
            else                -> "OFFLINE"
        }

        // Media historico
        var avgPing = 0f; var avgDl = 0f; var avgUl = 0f; var avgDrop = 0f
        var temHistorico = false
        val hist = historyData?.dish
        if (hist != null && hist.popPingLatencyMsCount > 0) {
            val n = minOf(900, hist.popPingLatencyMsCount)
            var sp = 0.0; var sd = 0.0; var su = 0.0; var sdr = 0.0
            for (i in 0 until n) {
                sp  += hist.getPopPingLatencyMs(i)
                sd  += hist.getDownlinkThroughputBps(i)
                su  += hist.getUplinkThroughputBps(i)
                sdr += hist.getPopPingDropRate(i)
            }
            avgPing = (sp / n).toFloat()
            avgDl   = (sd / n / 1_000_000.0).toFloat()
            avgUl   = (su / n / 1_000_000.0).toFloat()
            avgDrop = (sdr / n * 100.0).toFloat()
            temHistorico = true
        }

        val corStatus = when {
            serial != "---" && online -> "#22c55e"
            serial != "---"           -> "#f59e0b"
            else                      -> "#ef4444"
        }

        tvStatus.text = when {
            serial != "---" -> "Leitura OK! Serial: $serial"
            else            -> "Conectou mas serial nao disponivel neste estado"
        }
        tvStatus.setTextColor(Color.parseColor(corStatus))

        val sb = StringBuilder()
        sb.appendLine("=== IDENTIFICACAO ===")
        sb.appendLine("Serial Number: $serial")
        sb.appendLine("Hardware:      $hwVer")
        sb.appendLine("Firmware:      $swVer")
        sb.appendLine("")
        sb.appendLine("=== CONECTIVIDADE ===")
        sb.appendLine("Status:        $estadoStr")
        if (online && d != null) {
            sb.appendLine("Download:      ${"%.1f".format(dlMbps)} Mbps")
            sb.appendLine("Upload:        ${"%.1f".format(ulMbps)} Mbps")
            sb.appendLine("Latencia:      ${"%.0f".format(d.popPingLatencyMs)} ms")
            sb.appendLine("Packet Loss:   ${"%.2f".format(plPct)}%")
            sb.appendLine("SNR:           ${"%.1f".format(d.snr)} dB")
        } else {
            sb.appendLine("(aguardando conexao com satelite)")
        }
        if (temHistorico) {
            sb.appendLine("")
            sb.appendLine("=== MEDIA 15 MINUTOS ===")
            sb.appendLine("Download med:  ${"%.1f".format(avgDl)} Mbps")
            sb.appendLine("Upload med:    ${"%.1f".format(avgUl)} Mbps")
            sb.appendLine("Latencia med:  ${"%.0f".format(avgPing)} ms")
            sb.appendLine("Pkt Loss med:  ${"%.2f".format(avgDrop)}%")
        }
        sb.appendLine("")
        sb.appendLine("=== ANTENA ===")
        sb.appendLine("Obstrucao:     ${"%.1f".format(obsPct)}%")
        if (d != null) {
            sb.appendLine("Obstr.atual:   ${if (d.currentlyObstructed) "SIM" else "Nao"}")
            sb.appendLine("Azimute:       ${"%.1f".format(d.directionAzimuth)} graus")
            sb.appendLine("Elevacao:      ${"%.1f".format(d.directionElevation)} graus")
            sb.appendLine("GPS valido:    ${if (d.gpsValid) "Sim" else "Nao"}")
            sb.appendLine("Satelites GPS: ${d.gpsSats}")
        }
        sb.append("Uptime:        ${"%.1f".format(uptimeH)} h")

        tvResultado.text = sb.toString()

        val alertas = mutableListOf<String>()
        if (d != null) {
            if (d.alerts.motorsStuck)               alertas.add("Motor preso")
            if (d.alerts.thermalThrottle)            alertas.add("Thermal throttle")
            if (d.alerts.mastNotNearVertical)        alertas.add("Antena inclinada")
            if (d.alerts.diskObstructed)             alertas.add("Dish obstruido")
            if (d.alerts.slowEthernetSpeeds)         alertas.add("Ethernet lento")
            if (d.alerts.softwareInstallPending)     alertas.add("Atualizacao pendente")
            if (d.alerts.movingWhileNotMobile)       alertas.add("Movendo sem modo mobile")
            if (d.alerts.powerSupplyThermalThrottle) alertas.add("Fonte superaquecida")
            if (d.alerts.roamingNoService)           alertas.add("Roaming sem servico")
        }

        tvAlertas.text = if (alertas.isEmpty()) "Nenhum alerta ativo"
                         else alertas.joinToString("\n") { a -> "- $a" }
        tvAlertas.setTextColor(
            if (alertas.isEmpty()) Color.parseColor("#22c55e") else Color.parseColor("#ef4444")
        )

        cardResultado.visibility   = View.VISIBLE
        btnCompartilhar.visibility = View.VISIBLE
    }

    private fun compartilharWhatsApp() {
        val d       = statusData
        val agora   = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())
        val online  = d?.state == DishState.CONNECTED
        val dlMbps  = (d?.downlinkThroughputBps ?: 0f) / 1_000_000f
        val ulMbps  = (d?.uplinkThroughputBps ?: 0f) / 1_000_000f
        val plPct   = (d?.popPingDropRate ?: 0f) * 100f
        val obsPct  = (d?.fractionObstructed ?: 0f) * 100f
        val uptimeH = (d?.uptimeS?.toLong() ?: 0L) / 3600f
        val cli     = etCliente.text.toString().trim()
        val loc     = etLocal.text.toString().trim()
        val obs     = etObs.text.toString().trim()

        val serial = resolverSerial()
        val hwVer  = resolverHardware()
        val swVer  = resolverFirmware()

        val estadoStr = when (d?.state) {
            DishState.CONNECTED -> "Online"
            DishState.SEARCHING -> "Buscando sinal"
            DishState.BOOTING   -> "Iniciando"
            else                -> "Offline"
        }

        var avgPing = 0f; var avgDl = 0f; var avgUl = 0f; var avgDrop = 0f
        var temHistorico = false
        val hist = historyData?.dish
        if (hist != null && hist.popPingLatencyMsCount > 0) {
            val n = minOf(900, hist.popPingLatencyMsCount)
            var sp = 0.0; var sd = 0.0; var su = 0.0; var sdr = 0.0
            for (i in 0 until n) {
                sp += hist.getPopPingLatencyMs(i); sd += hist.getDownlinkThroughputBps(i)
                su += hist.getUplinkThroughputBps(i); sdr += hist.getPopPingDropRate(i)
            }
            avgPing = (sp/n).toFloat(); avgDl = (sd/n/1_000_000.0).toFloat()
            avgUl = (su/n/1_000_000.0).toFloat(); avgDrop = (sdr/n*100.0).toFloat()
            temHistorico = true
        }

        val alertas = mutableListOf<String>()
        if (d != null) {
            if (d.alerts.motorsStuck)               alertas.add("Motor preso")
            if (d.alerts.thermalThrottle)            alertas.add("Thermal throttle")
            if (d.alerts.mastNotNearVertical)        alertas.add("Antena inclinada")
            if (d.alerts.diskObstructed)             alertas.add("Dish obstruido")
            if (d.alerts.slowEthernetSpeeds)         alertas.add("Ethernet lento")
            if (d.alerts.softwareInstallPending)     alertas.add("Atualizacao pendente")
            if (d.alerts.movingWhileNotMobile)       alertas.add("Movendo sem modo mobile")
            if (d.alerts.powerSupplyThermalThrottle) alertas.add("Fonte superaquecida")
            if (d.alerts.roamingNoService)           alertas.add("Roaming sem servico")
        }

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
        sb.appendLine("*CONECTIVIDADE*")
        sb.appendLine("Status: $estadoStr")
        if (online && d != null) {
            sb.appendLine("Download: ${"%.1f".format(dlMbps)} Mbps")
            sb.appendLine("Upload: ${"%.1f".format(ulMbps)} Mbps")
            sb.appendLine("Latencia: ${"%.0f".format(d.popPingLatencyMs)} ms")
            sb.appendLine("Packet Loss: ${"%.2f".format(plPct)}%")
            sb.appendLine("SNR: ${"%.1f".format(d.snr)} dB")
        }
        if (temHistorico) {
            sb.appendLine("")
            sb.appendLine("*MEDIA 15 MINUTOS*")
            sb.appendLine("Download: ${"%.1f".format(avgDl)} Mbps")
            sb.appendLine("Upload: ${"%.1f".format(avgUl)} Mbps")
            sb.appendLine("Latencia: ${"%.0f".format(avgPing)} ms")
            sb.appendLine("Packet Loss: ${"%.2f".format(avgDrop)}%")
        }
        sb.appendLine("")
        sb.appendLine("*ANTENA*")
        sb.appendLine("Obstrucao: ${"%.1f".format(obsPct)}%")
        if (d != null) {
            sb.appendLine("Obstr.atual: ${if (d.currentlyObstructed) "SIM" else "Nao"}")
            sb.appendLine("Azimute: ${"%.1f".format(d.directionAzimuth)} graus")
            sb.appendLine("Elevacao: ${"%.1f".format(d.directionElevation)} graus")
            sb.appendLine("GPS valido: ${if (d.gpsValid) "Sim" else "Nao"}")
            sb.appendLine("Satelites GPS: ${d.gpsSats}")
        }
        sb.appendLine("Uptime: ${"%.1f".format(uptimeH)} h")
        sb.appendLine("")
        sb.appendLine("*ALERTAS*")
        if (alertas.isEmpty()) sb.appendLine("Nenhum alerta ativo")
        else for (a in alertas) sb.appendLine("- $a")
        if (obs.isNotBlank()) { sb.appendLine(""); sb.appendLine("Obs: $obs") }
        sb.appendLine("")
        sb.append("_HeadLink Brasil - Todos os direitos reservados_")

        val msg = sb.toString()
        try {
            startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, msg)
            })
        } catch (e: Exception) {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, msg)
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

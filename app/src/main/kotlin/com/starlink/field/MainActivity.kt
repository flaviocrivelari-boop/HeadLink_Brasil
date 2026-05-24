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

    private var currentData: DishGetStatusResponse? = null
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
                val resultado = withContext(Dispatchers.IO) {
                    val channel = ManagedChannelBuilder
                        .forAddress("192.168.100.1", 9200)
                        .usePlaintext()
                        .build()
                    try {
                        val stub = DeviceGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(10, TimeUnit.SECONDS)
                        val req = Request.newBuilder()
                            .setGetStatus(GetStatusRequest.getDefaultInstance())
                            .build()
                        stub.handle(req).dishGetStatus
                    } finally {
                        channel.shutdown().awaitTermination(2, TimeUnit.SECONDS)
                    }
                }
                currentData = resultado
                exibirDados(resultado)
            } catch (e: Exception) {
                setLoading(false)
                tvStatus.text = "Erro: ${e.message?.take(80)}"
                tvStatus.setTextColor(Color.parseColor("#ef4444"))
                Toast.makeText(this@MainActivity, "Verifique se esta no Wi-Fi da Starlink", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exibirDados(d: DishGetStatusResponse) {
        setLoading(false)
        val online  = d.state == DishState.CONNECTED
        val dlMbps  = d.downlinkThroughputBps / 1_000_000f
        val ulMbps  = d.uplinkThroughputBps / 1_000_000f
        val plPct   = d.popPingDropRate * 100f
        val obsPct  = d.fractionObstructed * 100f
        val uptimeH = d.uptimeS / 3600f
        tvStatus.text = if (online) "Leitura concluida!" else "Dish offline ou buscando sinal"
        tvStatus.setTextColor(if (online) Color.parseColor("#22c55e") else Color.parseColor("#f59e0b"))
        tvResultado.text = buildString {
            appendLine("Status:      ${if (online) "ONLINE" else "OFFLINE"}")
            appendLine("Download:    ${"%.1f".format(dlMbps)} Mbps")
            appendLine("Upload:      ${"%.1f".format(ulMbps)} Mbps")
            appendLine("Latencia:    ${"%.0f".format(d.popPingLatencyMs)} ms")
            appendLine("Packet Loss: ${"%.2f".format(plPct)}%")
            appendLine("Obstrucao:   ${"%.1f".format(obsPct)}%")
            appendLine("Uptime:      ${"%.1f".format(uptimeH)} h")
            append("Firmware:    ${d.softwareVersion.ifBlank { "---" }}")
        }
        val alertas = mutableListOf<String>()
        if (d.alerts.motorsStuck)         alertas += "Motor preso"
        if (d.alerts.thermalThrottle)     alertas += "Thermal throttle"
        if (d.alerts.mastNotNearVertical) alertas += "Antena inclinada"
        if (d.alerts.diskObstructed)      alertas += "Dish obstruido"
        if (d.alerts.slowEthernetSpeeds)  alertas += "Ethernet lento"
        tvAlertas.text = if (alertas.isEmpty()) "Nenhum alerta ativo" else alertas.joinToString("\n")
        tvAlertas.setTextColor(if (alertas.isEmpty()) Color.parseColor("#22c55e") else Color.parseColor("#ef4444"))
        cardResultado.visibility = View.VISIBLE
        btnCompartilhar.visibility = View.VISIBLE
    }

    private fun compartilharWhatsApp() {
        val d = currentData ?: return
        val agora  = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt","BR")).format(Date())
        val dlMbps = d.downlinkThroughputBps / 1_000_000f
        val ulMbps = d.uplinkThroughputBps / 1_000_000f
        val plPct  = d.popPingDropRate * 100f
        val obsPct = d.fractionObstructed * 100f
        val uptimeH = d.uptimeS / 3600f
        val online = d.state == DishState.CONNECTED
        val cli = etCliente.text.toString().trim()
        val loc = etLocal.text.toString().trim()
        val obs = etObs.text.toString().trim()
        val msg = buildString {
            appendLine("*RELATORIO STARLINK*")
            appendLine("Data: $agora")
            if (cli.isNotBlank()) appendLine("Cliente: $cli")
            if (loc.isNotBlank()) appendLine("Local: $loc")
            appendLine("")
            appendLine("*CONECTIVIDADE*")
            appendLine("Status: ${if (online) "Online" else "Offline"}")
            appendLine("Download: ${"%.1f".format(dlMbps)} Mbps")
            appendLine("Upload: ${"%.1f".format(ulMbps)} Mbps")
            appendLine("Latencia: ${"%.0f".format(d.popPingLatencyMs)} ms")
            appendLine("Packet Loss: ${"%.2f".format(plPct)}%")
            appendLine("")
            appendLine("*HARDWARE*")
            appendLine("Obstrucao: ${"%.1f".format(obsPct)}%")
            appendLine("Uptime: ${"%.1f".format(uptimeH)} h")
            appendLine("Firmware: ${d.softwareVersion.ifBlank{"---"}}")
            appendLine("")
            appendLine("*ALERTAS*")
            appendLine("Motor preso: ${if (d.alerts.motorsStuck) "SIM" else "Nao"}")
            appendLine("Thermal: ${if (d.alerts.thermalThrottle) "SIM" else "Nao"}")
            appendLine("Inclinada: ${if (d.alerts.mastNotNearVertical) "SIM" else "Nao"}")
            appendLine("Obstruida: ${if (d.alerts.diskObstructed) "SIM" else "Nao"}")
            if (obs.isNotBlank()) { appendLine(""); appendLine("Obs: $obs") }
            append("_App Starlink Field_")
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

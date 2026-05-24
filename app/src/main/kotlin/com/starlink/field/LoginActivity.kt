package com.starlink.field

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var etUsuario: EditText
    private lateinit var etSenha: EditText
    private lateinit var btnEntrar: Button
    private lateinit var tvStatus: TextView

    // Credenciais fixas
    private val USUARIO_VALIDO = "HeadLink"
    private val SENHA_VALIDA   = "admin"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        window.statusBarColor = Color.parseColor("#0a0f1e")

        etUsuario = findViewById(R.id.etUsuario)
        etSenha   = findViewById(R.id.etSenha)
        btnEntrar = findViewById(R.id.btnEntrar)
        tvStatus  = findViewById(R.id.tvStatus)

        btnEntrar.setOnClickListener { fazerLogin() }
    }

    private fun fazerLogin() {
        val usuario = etUsuario.text.toString().trim()
        val senha   = etSenha.text.toString()

        if (usuario.isEmpty() || senha.isEmpty()) {
            tvStatus.text = "Status do Sistema: Preencha todos os campos"
            tvStatus.setTextColor(Color.parseColor("#f59e0b"))
            return
        }

        if (usuario == USUARIO_VALIDO && senha == SENHA_VALIDA) {
            tvStatus.text = "Status do Sistema: Autenticado com sucesso"
            tvStatus.setTextColor(Color.parseColor("#00e676"))
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            tvStatus.text = "Status do Sistema: Credenciais invalidas"
            tvStatus.setTextColor(Color.parseColor("#ef4444"))
            etSenha.setText("")
            Toast.makeText(this, "Usuario ou senha incorretos", Toast.LENGTH_SHORT).show()
        }
    }
}

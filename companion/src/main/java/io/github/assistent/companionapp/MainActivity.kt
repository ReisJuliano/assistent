package io.github.assistent.companionapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val initialBase = uri?.getQueryParameter("base") ?: "http://192.168.0.10:8765/api/v1"
        val initialToken = uri?.getQueryParameter("token").orEmpty()
        setContent { CompanionApp(initialBase, initialToken) }
    }
}

@Composable
private fun CompanionApp(initialBase: String, initialToken: String) {
    val http = remember { OkHttpClient() }
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(initialBase) }
    var token by remember { mutableStateOf(initialToken) }
    var text by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Assistent", color = Color(0xFF3DE8FF), style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(base, { base = it }, label = { Text("API local") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("Token do celular") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(text, { text = it }, label = { Text("Mensagem") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch { output = post(http, base, token, "chat", """{"text":${json(text)},"speak":true}""") }
                    }) { Text("Chat") }
                    Button(onClick = {
                        scope.launch { output = post(http, base, token, "mesh/broadcast", """{"text":${json(text)}}""") }
                    }) { Text("Falar em casa") }
                }
                Button(onClick = {
                    scope.launch { output = get(http, base, token, "lists/compras") }
                }) { Text("Lista de compras") }
                Text(output, color = Color.White, modifier = Modifier.fillMaxWidth().background(Color(0xFF101820), RoundedCornerShape(8.dp)).padding(12.dp))
            }
        }
    }
}

private suspend fun post(http: OkHttpClient, base: String, token: String, path: String, body: String): String = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("${base.trimEnd('/')}/$path").header("Authorization", "Bearer $token")
        .post(body.toRequestBody("application/json".toMediaType())).build()
    http.newCall(req).execute().use { it.body?.string().orEmpty() }
}

private suspend fun get(http: OkHttpClient, base: String, token: String, path: String): String = withContext(Dispatchers.IO) {
    val req = Request.Builder().url("${base.trimEnd('/')}/$path").header("Authorization", "Bearer $token").build()
    http.newCall(req).execute().use { it.body?.string().orEmpty() }
}

private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

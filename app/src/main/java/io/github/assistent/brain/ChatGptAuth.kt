package io.github.assistent.brain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import io.github.assistent.core.AppJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID

/** Credentials of the ChatGPT account connected through "Sign in with ChatGPT" (kept only on this device). */
@Serializable
data class ChatGptCredentials(
    /** Stable per-install id sent as `ext_agent_host_id` (never an email or user id). */
    val hostId: String = "urn:uuid:${UUID.randomUUID()}",
    /** `oaiapp_…` client id issued on first registration; reused for later sign-ins and refreshes. */
    val clientId: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
    val email: String = "",
    val scope: String = "",
)

/**
 * Official "Sign in with ChatGPT" (plan usage for open-source / local apps): lets the robot use the owner's
 * ChatGPT Plus/Pro allowance on the public Responses API, without a proxy on a PC or an API key.
 *
 * OAuth 2 + OIDC with PKCE: the system browser opens `auth.openai.com`, the user approves, and the code comes back
 * to a short-lived loopback listener on `127.0.0.1` (only while signing in). Tokens live in a private file in
 * `filesDir` — not in `config.json`, so they are never shared through the mesh or the config API.
 * See https://developers.openai.com/siwc/token-sharing-open-source
 */
class ChatGptAuth(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "chatgpt-auth.json")
    private val lock = Mutex()
    @Volatile private var creds: ChatGptCredentials = load()
    private val _status = MutableStateFlow(describe())
    val status: StateFlow<String> = _status
    private var listenJob: Job? = null

    val signedIn get() = creds.refreshToken.isNotBlank()
    val email get() = creds.email

    /** Opens the browser to sign in; the result arrives on the loopback listener. */
    fun startSignIn() {
        listenJob?.cancel()
        val verifier = randomToken(48)
        val state = randomToken(24)
        val nonce = randomToken(24)
        val server = (PORTS).firstNotNullOfOrNull { p ->
            runCatching { ServerSocket(p, 4, InetAddress.getByName("127.0.0.1")) }.getOrNull()
        } ?: run { _status.value = "Não consegui abrir a porta local para o login"; return }
        val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
        val c = creds
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to c.clientId.ifBlank { "dynamic_agent_client" },
            "redirect_uri" to redirect,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())),
            "ext_agent_host_id" to c.hostId,
        )
        if (c.clientId.isBlank()) params["agent_name_hint"] = APP_NAME
        if (c.email.isNotBlank()) params["login_hint"] = c.email
        val url = AUTHORIZE + "?" + params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        _status.value = "Aguardando login no navegador…"
        listenJob = scope.launch(Dispatchers.IO) {
            try {
                server.use { s ->
                    s.soTimeout = 10 * 60_000
                    while (true) {
                        val sock = s.accept()
                        val line = sock.getInputStream().bufferedReader().readLine().orEmpty()
                        val target = line.split(" ").getOrNull(1).orEmpty()
                        if (!target.startsWith("/auth/callback")) { sock.close(); continue }
                        val q = target.substringAfter('?', "").split('&').filter { '=' in it }
                            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                        val result = runCatching { finish(q, state, nonce, verifier, redirect) }
                            .getOrElse { if (it is CancellationException) throw it; "Falhou: ${it.message}" }
                        respond(sock, result)
                        _status.value = result
                        bringAppToFront()
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = "Login não concluído (${e.message})"
            }
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun finish(q: Map<String, String>, state: String, nonce: String, verifier: String, redirect: String): String {
        q["error"]?.let { return "Login cancelado: ${q["error_description"] ?: it}" }
        require(q["state"] == state) { "state inválido" }
        val code = q["code"] ?: error("sem código")
        val clientId = q["client_id"]?.takeIf { it.isNotBlank() } ?: creds.clientId.ifBlank { error("client_id não emitido") }
        // Persist the issued client id before the exchange: a retry after an expired code must reuse it.
        save(creds.copy(clientId = clientId))
        val tokens = tokenRequest(
            FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId).add("code", code)
                .add("code_verifier", verifier).add("redirect_uri", redirect).add("resource", RESOURCE).build(),
        )
        val idToken = tokens["id_token"]?.jsonPrimitive?.contentOrNull ?: error("sem id_token")
        val claims = verifyIdToken(idToken, clientId, nonce)
        val granted = tokens["scope"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (granted.isNotBlank() && "chatgpt.tokens.use.direct" !in granted) {
            return "Conta conectada, mas o uso do plano não foi autorizado (precisa de ChatGPT Plus/Pro e aceitar a permissão)."
        }
        lock.withLock { store(tokens, claims["email"]?.jsonPrimitive?.contentOrNull.orEmpty(), clientId) }
        return "Conectado como ${creds.email.ifBlank { "conta ChatGPT" }} ✓"
    }

    /** A valid access token, refreshed when close to expiry. Throws when not signed in / disconnected. */
    suspend fun accessToken(forceRefresh: Boolean = false): String = lock.withLock {
        val c = creds
        if (c.refreshToken.isBlank()) throw LlmException("ChatGPT não conectado: Configurações → Cérebro → Entrar com ChatGPT")
        if (!forceRefresh && c.accessToken.isNotBlank() && System.currentTimeMillis() < c.expiresAt - 5 * 60_000) return c.accessToken
        val tokens = try {
            tokenRequest(
                FormBody.Builder().add("grant_type", "refresh_token").add("client_id", c.clientId)
                    .add("refresh_token", c.refreshToken).add("resource", RESOURCE).build(),
            )
        } catch (e: RefreshRejected) {
            // Terminal refresh errors only (never on network trouble): the user must sign in again.
            save(c.copy(accessToken = "", refreshToken = "", expiresAt = 0L))
            _status.value = "Conta ChatGPT desconectada: entre de novo"
            throw LlmException("ChatGPT desconectado (${e.message}): entre de novo nas configurações")
        }
        store(tokens, c.email, c.clientId)
        creds.accessToken
    }

    fun signOut() {
        listenJob?.cancel()
        save(ChatGptCredentials(hostId = creds.hostId, clientId = creds.clientId))
        _status.value = describe()
    }

    private fun store(tokens: JsonObject, email: String, clientId: String) {
        val c = creds
        save(
            c.copy(
                clientId = clientId,
                accessToken = tokens["access_token"]?.jsonPrimitive?.contentOrNull ?: error("sem access_token"),
                // Refresh tokens rotate: always keep the latest replacement.
                refreshToken = tokens["refresh_token"]?.jsonPrimitive?.contentOrNull ?: c.refreshToken,
                expiresAt = System.currentTimeMillis() + (tokens["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L) * 1000,
                email = email.ifBlank { c.email },
                scope = tokens["scope"]?.jsonPrimitive?.contentOrNull ?: c.scope,
            ),
        )
        _status.value = describe()
    }

    private suspend fun tokenRequest(body: FormBody): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(TOKEN).post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val json = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!r.isSuccessful) {
                val err = json?.get("error")?.let { e -> runCatching { e.jsonPrimitive.content }.getOrNull() ?: e.jsonObject["code"]?.jsonPrimitive?.contentOrNull }
                if (err in TERMINAL_REFRESH) throw RefreshRejected(err.orEmpty())
                throw LlmException("OAuth HTTP ${r.code}: ${text.take(200)}")
            }
            json ?: throw LlmException("OAuth: resposta inválida")
        }
    }

    /** Checks signature (RS256, OpenAI JWKS), issuer, audience, nonce and expiry; returns the claims. */
    private suspend fun verifyIdToken(jwt: String, clientId: String, nonce: String): JsonObject = withContext(Dispatchers.IO) {
        val parts = jwt.split('.')
        require(parts.size == 3) { "id_token malformado" }
        val header = AppJson.parseToJsonElement(String(b64urlDecode(parts[0]))).jsonObject
        val claims = AppJson.parseToJsonElement(String(b64urlDecode(parts[1]))).jsonObject
        require(header["alg"]?.jsonPrimitive?.contentOrNull == "RS256") { "alg inesperado" }
        val kid = header["kid"]?.jsonPrimitive?.contentOrNull
        val keys = http.newCall(Request.Builder().url(JWKS).build()).execute().use { r ->
            AppJson.parseToJsonElement(r.body!!.string()).jsonObject["keys"]!!.jsonArray.map { it.jsonObject }
        }
        val jwk = keys.firstOrNull { kid == null || it["kid"]?.jsonPrimitive?.contentOrNull == kid } ?: error("chave do id_token não encontrada")
        val pub = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(
                BigInteger(1, b64urlDecode(jwk["n"]!!.jsonPrimitive.content)),
                BigInteger(1, b64urlDecode(jwk["e"]!!.jsonPrimitive.content)),
            ),
        )
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(pub); update("${parts[0]}.${parts[1]}".toByteArray()); verify(b64urlDecode(parts[2]))
        }
        require(ok) { "assinatura do id_token inválida" }
        require(claims["iss"]?.jsonPrimitive?.contentOrNull == ISSUER) { "issuer inválido" }
        val aud = claims["aud"]
        val audOk = runCatching { aud!!.jsonPrimitive.content == clientId }.getOrNull()
            ?: runCatching { aud!!.jsonArray.any { it.jsonPrimitive.content == clientId } }.getOrDefault(false)
        require(audOk) { "audience inválida" }
        require(claims["nonce"]?.jsonPrimitive?.contentOrNull == nonce) { "nonce inválido" }
        val exp = claims["exp"]?.jsonPrimitive?.longOrNull ?: 0L
        require(exp * 1000 > System.currentTimeMillis() - 60_000) { "id_token expirado" }
        claims
    }

    private fun respond(sock: java.net.Socket, message: String) = runCatching {
        val html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
            "<body style='background:#000;color:#3de8ff;font:20px sans-serif;text-align:center;padding-top:30vh'>" +
            message.replace("<", "&lt;") + "<p style='color:#888;font-size:15px'>Pode voltar para o robô.</p>"
        val bytes = html.toByteArray()
        sock.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
        sock.close()
    }

    private fun bringAppToFront() = runCatching {
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            ?.let(context::startActivity)
    }

    private fun describe() = when {
        creds.refreshToken.isNotBlank() -> "Conectado: ${creds.email.ifBlank { "conta ChatGPT" }}"
        else -> "Não conectado"
    }

    private fun load(): ChatGptCredentials = runCatching {
        AppJson.decodeFromString(ChatGptCredentials.serializer(), file.readText())
    }.getOrElse { ChatGptCredentials().also { c -> runCatching { file.writeText(AppJson.encodeToString(ChatGptCredentials.serializer(), c)) } } }

    private fun save(c: ChatGptCredentials) {
        creds = c
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(AppJson.encodeToString(ChatGptCredentials.serializer(), c))
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "could not save credentials", it) }
    }

    private class RefreshRejected(code: String) : Exception(code)

    companion object {
        private const val TAG = "ChatGptAuth"
        private const val APP_NAME = "Assistent"
        const val ISSUER = "https://auth.openai.com"
        const val AUTHORIZE = "https://auth.openai.com/api/accounts/authorize"
        const val TOKEN = "https://auth.openai.com/api/accounts/oauth/token"
        const val JWKS = "https://auth.openai.com/.well-known/jwks.json"
        const val RESOURCE = "https://api.openai.com/v1"
        const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        private val PORTS = listOf(1455, 1456, 1457, 1458)
        private val TERMINAL_REFRESH = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused",
        )

        private val rng = SecureRandom()
        private fun randomToken(bytes: Int) = b64url(ByteArray(bytes).also(rng::nextBytes))
        private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        private fun b64urlDecode(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

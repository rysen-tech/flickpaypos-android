package uk.flickpay.flickpaypos

import android.content.Context
import androidx.core.text.HtmlCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.util.Locale

class HwProxyServer(
    context: Context,
    private val printer: UsbEscPosPrinter,
    port: Int = 8070
) : NanoHTTPD("0.0.0.0", port) {

    private val appContext = context.applicationContext
    private val tag = "FlickpayPOSProxy"

    override fun serve(session: IHTTPSession): Response {
        val origin = session.headers["origin"]?.trim().orEmpty()
        val requestedHeaders = session.headers["access-control-request-headers"]?.trim().orEmpty()
        val requestedMethod = session.headers["access-control-request-method"]?.trim().orEmpty()
        val allowOrigin = resolveAllowedOrigin(origin)
        return try {
            val response = if (session.method == Method.OPTIONS) {
                // Some Android WebView versions are stricter with 204 preflight responses.
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
            } else {
                route(session)
            }
            cors(
                response = response,
                allowOrigin = allowOrigin,
                requestedHeaders = requestedHeaders,
                requestedMethod = requestedMethod
            )
        } catch (t: Throwable) {
            AppRuntimeLog.e(appContext, tag, "Proxy request failed: ${session.uri}", t)
            val error = JSONObject().apply {
                put("error", "Internal server error")
                put("message", t.message ?: "unknown")
            }
            cors(
                response = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    error.toString()
                ),
                allowOrigin = allowOrigin,
                requestedHeaders = requestedHeaders,
                requestedMethod = requestedMethod
            )
        }
    }

    private fun route(session: IHTTPSession): Response {
        return when (canonicalPath(session.uri)) {
            "/" -> json(Response.Status.OK, JSONObject().apply {
                put("ok", true)
                put("service", "flickpaypos-android-service")
            })

            "/hw_proxy/hello" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ping")
                .also { printer.warmupActivePrinterAsync() }

            "/hw_proxy/handshake" -> {
                val rpc = parseRpc(readBody(session))
                rpcResult(rpc.id, true)
            }

            "/hw_proxy/status_json" -> {
                printer.warmupActivePrinterAsync()
                val hasPrinter = printer.hasAnyPrinter()
                val hasPermission = printer.hasPermissionForCurrentPrinter()
                val printerStatus = when {
                    hasPrinter && hasPermission -> "connected"
                    hasPrinter && !hasPermission -> "connecting"
                    else -> "disconnected"
                }

                // Odoo expects a drivers map in status_json result.
                val result = JSONObject().apply {
                    put(
                        "printer",
                        JSONObject().apply {
                            put("status", printerStatus)
                            put("name", printer.currentPrinterName())
                            put("has_permission", hasPermission)
                        }
                    )
                    put(
                        "scanner",
                        JSONObject().apply { put("status", "disconnected") }
                    )
                    put(
                        "scale",
                        JSONObject().apply { put("status", "disconnected") }
                    )
                    put(
                        "cashdrawer",
                        JSONObject().apply {
                            put("status", if (printerStatus == "connected") "connected" else "disconnected")
                        }
                    )
                }
                val payload = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", "")
                    put("result", result)
                }
                json(Response.Status.OK, payload)
            }

            "/hw_proxy/scale_read" -> {
                val rpc = parseRpc(readBody(session))
                rpcResult(rpc.id, false)
            }

            "/hw_proxy/default_printer_action" -> {
                val rpc = parseRpc(readBody(session))
                val ok = handleDefaultPrinterAction(rpc.params)
                AppRuntimeLog.i(appContext, tag, "default_printer_action result=$ok")
                rpcResult(rpc.id, ok)
            }

            "/hw_proxy/default_printer_label_action" -> {
                val rpc = parseRpc(readBody(session))
                val ok = handleDefaultPrinterAction(rpc.params)
                AppRuntimeLog.i(appContext, tag, "default_printer_label_action result=$ok")
                rpcResult(rpc.id, ok)
            }

            "/hw_proxy/print_xml_receipt" -> {
                val rpc = parseRpc(readBody(session))
                val html = rpc.params?.optString("receipt").orEmpty()
                val ok = if (html.isNotBlank()) {
                    printer.printPlainText(stripHtml(html))
                } else {
                    false
                }
                AppRuntimeLog.i(appContext, tag, "print_xml_receipt result=$ok")
                rpcResult(rpc.id, ok)
            }

            else -> json(Response.Status.NOT_FOUND, JSONObject().apply { put("error", "Not found") })
        }
    }

    private fun canonicalPath(rawUri: String?): String {
        val value = rawUri?.trim().orEmpty()
        if (value.isBlank()) return "/"
        val noQuery = value.substringBefore('?').substringBefore('#')
        val parts = noQuery.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return "/"
        return "/" + parts.joinToString("/")
    }

    private fun handleDefaultPrinterAction(params: JSONObject?): Boolean {
        val data = when {
            params == null -> JSONObject()
            params.has("data") && params.opt("data") is JSONObject -> params.optJSONObject("data") ?: JSONObject()
            else -> params
        }

        val action = data.optString("action").lowercase(Locale.ROOT)
        if (action.contains("cash")) {
            return printer.openCashDrawer()
        }

        val receipt = data.opt("receipt")
        if (receipt == null || receipt == JSONObject.NULL) {
            return false
        }

        return when (receipt) {
            is JSONObject -> {
                val payload = receipt.optString("data")
                val paperWidthMm = receipt.optDouble("paperWidthMm", 80.0).toFloat()
                val mode = receipt.optString("mode").lowercase(Locale.ROOT)
                val isBase64 = receipt.optBoolean("isBase64") ||
                    receipt.optBoolean("is_base64") ||
                    mode.contains("base64", ignoreCase = true)
                val rawEscpos = receipt.optBoolean("rawEscpos") ||
                    receipt.optBoolean("raw_escpos") ||
                    mode.contains("escpos")

                if (payload.isBlank()) {
                    false
                } else if (rawEscpos) {
                    printer.printEscposText(payload)
                } else if (isBase64 || looksLikeBase64(payload)) {
                    printer.printBase64Image(payload, paperWidthMm)
                } else {
                    printer.printPlainText(stripHtml(payload))
                }
            }

            is String -> {
                if (looksLikeBase64(receipt)) {
                    printer.printBase64Image(receipt, 80f)
                } else {
                    printer.printPlainText(stripHtml(receipt))
                }
            }

            else -> false
        }
    }

    private fun looksLikeBase64(value: String): Boolean {
        if (value.length < 64) return false
        return BASE64_REGEX.matches(value.trim())
    }

    private fun stripHtml(html: String): String {
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    private data class RpcPacket(val id: Any?, val params: JSONObject?)

    private fun parseRpc(body: String): RpcPacket {
        if (body.isBlank()) return RpcPacket("", JSONObject())
        return try {
            val root = JSONObject(body)
            val id = root.opt("id")
            val params = root.opt("params") as? JSONObject
            RpcPacket(id, params)
        } catch (_: Throwable) {
            RpcPacket("", JSONObject())
        }
    }

    private fun readBody(session: IHTTPSession): String {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun rpcResult(id: Any?, result: Any): Response {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: "")
            put("result", result)
        }
        return json(Response.Status.OK, payload)
    }

    private fun json(status: Response.Status, payload: JSONObject): Response {
        return newFixedLengthResponse(status, "application/json", payload.toString())
    }

    private fun cors(
        response: Response,
        allowOrigin: String,
        requestedHeaders: String,
        requestedMethod: String
    ): Response {
        val allowHeaders = if (requestedHeaders.isNotBlank()) {
            requestedHeaders
        } else {
            "Origin, X-Requested-With, Content-Type, Accept, X-Debug-Mode, Authorization, X-Openerp-Session-Id, X-CSRFToken, X-CSRF-Token"
        }
        val allowMethodSet = linkedSetOf("POST", "GET", "OPTIONS")
        val requested = requestedMethod.trim().uppercase(Locale.ROOT)
        if (requested.isNotBlank()) {
            allowMethodSet.add(requested)
        }
        val allowMethods = allowMethodSet.joinToString(", ")
        response.addHeader("Access-Control-Allow-Origin", allowOrigin)
        response.addHeader("Vary", "Origin")
        // Wildcard origin cannot be combined with credentials in CORS.
        if (allowOrigin != "*") {
            response.addHeader("Access-Control-Allow-Credentials", "true")
        }
        response.addHeader("Access-Control-Allow-Methods", allowMethods)
        response.addHeader("Access-Control-Allow-Headers", allowHeaders)
        response.addHeader("Access-Control-Max-Age", "86400")
        response.addHeader("Access-Control-Allow-Private-Network", "true")
        response.addHeader("Connection", "close")
        return response
    }

    private fun resolveAllowedOrigin(rawOrigin: String): String {
        val origin = rawOrigin.trim()
        if (origin.isNotBlank() && origin.lowercase(Locale.ROOT) != "null") {
            return origin
        }
        val prefs = appContext.getSharedPreferences("flickpaypos_app", Context.MODE_PRIVATE)
        val accountHost = prefs.getString("account_host", "")?.trim().orEmpty()
        if (accountHost.isNotBlank()) {
            return "https://$accountHost"
        }
        return "https://app.flickpay.co.uk"
    }

    companion object {
        private val BASE64_REGEX = Regex("^[A-Za-z0-9+/=\\s]+$")
    }
}

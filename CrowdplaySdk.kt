package com.crowdplay;

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import com.google.firebase.messaging.RemoteMessage
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterFragment
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.Serializable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

fun Bundle.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    for (key in keySet()) {
        val value = get(key)
        map[key] = when (value) {
            is Bundle -> this.getBundle(key)?.toMap()  // Recursively handle nested bundles
            is String -> this.getString(key)
            is Int -> this.getInt(key)
            is Boolean -> this.getBoolean(key)
            is Float -> this.getFloat(key)
            is Double -> this.getDouble(key)
            is Long -> this.getLong(key)
            is Short -> this.getShort(key)
            is Char -> this.getChar(key)
            is Byte -> this.getByte(key)
            is CharSequence -> this.getCharSequence(key)
            is Parcelable -> this.getParcelable<Parcelable>(key)
            is Serializable -> this.getSerializable(key)
            else -> this.get(key)  // Catch-all for other types
        }
    }
    return map
}

class CrowdplaySdk : FlutterPlugin , MethodChannel.MethodCallHandler {

    private var apiKey = ""
    private var authToken: MutableMap<String, String> = mutableMapOf()
    private var linkedAccounts: MutableList<MutableMap<String,String>> = mutableListOf()
    private var appUrlScheme = ""

    private var initialized = false

    var flutterEngine: FlutterEngine? = null
        private set
    private var channel: MethodChannel? = null
    private var lastActivityContext: Activity? = null
    private var childActivityId: Int = 98374897

    var showVenueNextWalletHandler: (() -> Unit)? = null
    var onAuthStateChanged: ((CrowdPlayAuthState) -> Unit)? = null
    var onPointsChanged: ((Double) -> Unit)? = null
    private var pendingAuthFuture: CompletableFuture<CrowdPlayAuthResult>? = null
    var tokenRefreshHandler: (() -> CompletableFuture<Map<String, String>?>)? = null

    private var pointsResult: CompletableFuture<Int?> = CompletableFuture<Int?>();

    companion object {
        private const val ENGINE_CACHE_KEY = "flutter_activity_id"
        private var instance: CrowdplaySdk? = null
        fun getInstance(): CrowdplaySdk {
            if (instance == null) {
                instance = CrowdplaySdk()
            }
            return instance!!
        }
    }

    fun initialize(apiKey: String, appUrlScheme: String, context: Activity) {
        if (this.flutterEngine != null) {
            return;
        }

        this.apiKey = apiKey
        this.appUrlScheme = appUrlScheme

        this.initialized = true
        lastActivityContext = context

        if (flutterEngine == null) {
            flutterEngine = FlutterEngine(context)

            flutterEngine!!.plugins.add(this)
            flutterEngine!!.navigationChannel.setInitialRoute("/")
            flutterEngine!!.dartExecutor.executeDartEntrypoint(
                DartExecutor.DartEntrypoint.createDefault()
            )
            FlutterEngineCache
                .getInstance()
                .put(ENGINE_CACHE_KEY, flutterEngine)
        }
    }

    private fun ensureEngineReady() {
        if (!initialized) {
            throw Exception("Crowdplay SDK is not initialized. Please call initialize before calling this function.")
        }
        // Re-cache engine if it was lost due to process death
        if (FlutterEngineCache.getInstance().get(ENGINE_CACHE_KEY) == null && flutterEngine != null) {
            FlutterEngineCache.getInstance().put(ENGINE_CACHE_KEY, flutterEngine)
        }
    }

    fun startActivity() {
        ensureEngineReady()

        val flutterActivity = FlutterActivity
            .withCachedEngine(ENGINE_CACHE_KEY)
            .build(lastActivityContext!!)
        lastActivityContext!!.startActivityForResult(flutterActivity, childActivityId);
    }

    fun getFragment(): FlutterFragment {
        ensureEngineReady()
        return FlutterFragment.withCachedEngine(ENGINE_CACHE_KEY).build()
    }

    fun handleNotificationActivation(bundle : Bundle?) : Boolean {
        if (channel == null ||
            bundle == null || bundle.getString("source") != "crowdplay"
        ) {
            return false;
        }

        startActivity();

        fun launchActivity() = runBlocking {
            delay(1000L);
            channel!!.invokeMethod("handleNotification", bundle.toMap());
        }

        launchActivity();
        return true;
    }

    fun handleNotification(remoteMessage: RemoteMessage?): Boolean {
        if (channel == null ||
            remoteMessage == null || remoteMessage.data.getOrDefault("source", null) == null ||
            remoteMessage.data.getOrDefault("source", null) != "crowdplay"
        ) {
            return false;
        }

        startActivity();

        fun launchActivity() = runBlocking {
            delay(1000L);
            channel!!.invokeMethod("handleNotification", remoteMessage.data);
        }

        launchActivity();
        return true;
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "initialization" -> {
                result.success(apiKey)
            }
            "CLOSE_CROWDPLAY" -> {
                lastActivityContext?.finishActivity(childActivityId);
                result.success(null)
            }
            "getAuthToken" -> {
                result.success(authToken)
            }
            "getLinkedAccounts" -> {
                result.success(linkedAccounts)
            }
            "getUrlScheme" -> {
                result.success(appUrlScheme)
            }
            "showVenueNextWallet" -> {
                showVenueNextWalletHandler?.invoke()
                result.success(true)
            }
            "authResult" -> {
                val args = call.arguments as? Map<*, *>
                if (args != null) {
                    val authResult = CrowdPlayAuthResult(
                        success = args["success"] as? Boolean ?: false,
                        method = args["method"] as? String,
                        error = args["error"] as? String,
                        errorCode = args["errorCode"] as? String
                    )
                    pendingAuthFuture?.complete(authResult)
                    pendingAuthFuture = null
                }
                result.success(null)
            }
            "requestFreshToken" -> {
                val handler = tokenRefreshHandler
                if (handler != null) {
                    handler().thenAccept { freshToken ->
                        result.success(freshToken)
                    }.exceptionally { _ ->
                        result.success(null)
                        null
                    }
                } else {
                    result.success(null)
                }
            }
            "onAuthStateChanged" -> {
                val args = call.arguments as? Map<*, *>
                val stateStr = args?.get("state") as? String
                if (stateStr != null) {
                    try {
                        val state = CrowdPlayAuthState.valueOf(stateStr)
                        onAuthStateChanged?.invoke(state)
                    } catch (_: IllegalArgumentException) {}
                }
                result.success(null)
            }
            "onPointsChanged" -> {
                val args = call.arguments as? Map<*, *>
                val points = (args?.get("points") as? Number)?.toDouble()
                if (points != null) {
                    onPointsChanged?.invoke(points)
                }
                result.success(null)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onAttachedToEngine(binding: FlutterPluginBinding) {
        val messenger = binding.binaryMessenger
        channel = MethodChannel(
            messenger,
            "crowdplay.flutter",
            StandardMethodCodec.INSTANCE
        )
        channel!!.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
        if (channel != null) {
            channel!!.setMethodCallHandler(null)
        }
        channel = null
    }

    fun setAuthToken(authToken: String, provider: String): CompletableFuture<CrowdPlayAuthResult> {
        this.authToken["provider"] = provider
        this.authToken["token"] = authToken

        val future = CompletableFuture<CrowdPlayAuthResult>()
        pendingAuthFuture = future

        if (flutterEngine != null) {
            channel!!.invokeMethod("performTokenLogin", this.authToken);
        } else {
            future.complete(CrowdPlayAuthResult(
                success = false,
                error = "SDK not initialized",
                errorCode = "not_initialized"
            ))
        }

        return future
    }

    fun logout(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()

        if (flutterEngine != null && channel != null) {
            channel!!.invokeMethod("performLogout", null, object : MethodChannel.Result {
                override fun success(result: Any?) {
                    future.complete(result as? Boolean ?: false)
                }
                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    future.complete(false)
                }
                override fun notImplemented() {
                    future.complete(false)
                }
            })
        } else {
            future.complete(false)
        }

        return future
    }

    fun linkAccount(providerToken: String, provider: String) {
        val providerInfo: MutableMap<String,String> = mutableMapOf()
        providerInfo["token"] = providerToken
        providerInfo["provider"] = provider

        linkedAccounts.add(providerInfo)

        if (flutterEngine != null) {
            channel!!.invokeMethod("linkAccounts", mapOf("accounts" to providerInfo));
        }
    }

    fun handleAppLink(appLink: Uri): Boolean {
        if (flutterEngine == null || (appLink.host != "crowdplay" && appLink.host != "rtl-callback")) {
            return false;
        }

        channel!!.invokeMethod("handleAppLink", appLink.toString());

        fun launchActivity() = runBlocking {
            delay(300L);
            startActivity();
        }

        launchActivity();
        return true;
    }

    fun loggedIn(): CompletableFuture<Boolean?> {
        val future = CompletableFuture<Boolean?>()

        if (flutterEngine != null) {
            channel!!.invokeMethod("loggedIn", null, object : MethodChannel.Result {
                override fun success(result: Any?) {
                    future.complete(result as? Boolean)
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    future.completeExceptionally(
                        Exception("Flutter error: $errorCode - $errorMessage")
                    )
                }

                override fun notImplemented() {
                    future.completeExceptionally(
                        NotImplementedError("Flutter method not implemented: loggedIn")
                    )
                }
            });
        } else {
            future.complete(null)
        }

        return future;
    }

    fun getPointsBalance(): CompletableFuture<Int?> {
        val future = CompletableFuture<Int?>()

        if (flutterEngine != null) {
            channel!!.invokeMethod("getPointsBalance", null, object : MethodChannel.Result {
                override fun success(result: Any?) {
                    future.complete((result as? Number)?.toInt())
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    future.completeExceptionally(
                        Exception("Flutter error: $errorCode - $errorMessage")
                    )
                }

                override fun notImplemented() {
                    future.completeExceptionally(
                        NotImplementedError("Flutter method not implemented: getPointsBalance")
                    )
                }
            });
        } else {
            future.complete(null)
        }

        return future;
    }

    fun getLoyaltyCodeAndDescription(): CompletableFuture<Map<String,String>?> {
        val future = CompletableFuture<Map<String,String>?>()

        if (flutterEngine != null) {
            channel!!.invokeMethod("getLoyaltyCodeAndDescription", null, object : MethodChannel.Result {
                override fun success(result: Any?) {
                    future.complete(result as? Map<String,String>);
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    future.completeExceptionally(
                        Exception("Flutter error: $errorCode - $errorMessage")
                    )
                }

                override fun notImplemented() {
                    future.completeExceptionally(
                        NotImplementedError("Flutter method not implemented: getLoyaltyCodeAndDescription")
                    )
                }
            });
        } else {
            future.complete(null)
        }

        return future;
    }
}

data class CrowdPlayAuthResult(
    val success: Boolean,
    val method: String? = null,
    val error: String? = null,
    val errorCode: String? = null
)

enum class CrowdPlayAuthState {
    loggedIn,
    loggedOut,
    authenticating,
    loggingOut
}

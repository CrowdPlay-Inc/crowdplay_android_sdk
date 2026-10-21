package com.crowdplay;

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import com.google.firebase.messaging.RemoteMessage
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterFragment
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.plugins.GeneratedPluginRegistrant
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec
import java.io.Serializable
import java.util.concurrent.CompletableFuture

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
    private var appContext: android.content.Context? = null
    private var lastActivityContext: Activity? = null
    private var childActivityId: Int = 37489
    private var cachedFragment: FlutterFragment? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var showVenueNextWalletHandler: (() -> Unit)? = null
    var onAuthStateChanged: ((CrowdPlayAuthState) -> Unit)? = null
    var onPointsChanged: ((Double) -> Unit)? = null
    // Fired whenever the user's loyalty code changes (null = signed out).
    var onLoyaltyCodeChanged: ((String?, String?) -> Unit)? = null
    private var pendingAuthFuture: CompletableFuture<CrowdPlayAuthResult>? = null
    var tokenRefreshHandler: (() -> CompletableFuture<Map<String, String>?>)? = null

    /**
     * Invoked whenever the SDK needs to surface its UI — either because the
     * host called startActivity() directly, or because a notification /
     * deep link arrived that should bring CrowdPlay to the foreground.
     *
     * When this handler is set, the SDK will NOT auto-present (start the
     * Flutter activity). The host app is responsible for making the SDK's
     * surface visible — typically by switching to the tab/fragment that
     * embeds getFragment(). When this handler is null, the SDK falls back
     * to the legacy behavior of starting its own takeover activity.
     */
    var onPresentRequested: (() -> Unit)? = null

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
        this.appContext = context.applicationContext
        lastActivityContext = context

        buildAndCacheEngine(appContext!!)
    }

    /**
     * Constructs a FlutterEngine, wires up plugins + this instance, starts
     * the Dart entrypoint, and installs the engine in FlutterEngineCache
     * under our key. Extracted so ensureEngineReady() can rebuild after
     * process death without duplicating the setup.
     */
    private fun buildAndCacheEngine(context: android.content.Context) {
        val engine = FlutterEngine(context)
        GeneratedPluginRegistrant.registerWith(engine)
        engine.plugins.add(this)
        engine.navigationChannel.setInitialRoute("/")
        engine.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint.createDefault()
        )
        FlutterEngineCache.getInstance().put(ENGINE_CACHE_KEY, engine)
        flutterEngine = engine
    }

    /**
     * Called before any operation that requires the FlutterEngine.
     *
     * After Android kills the process for memory pressure, both the SDK
     * singleton and FlutterEngineCache are wiped. When the host restores
     * MainActivity and calls back into the SDK, `initialized` remains false
     * so callers get a clear error. When the host has already called
     * initialize() but the engine/cache disappeared some other way (rare),
     * we rebuild in place so downstream FlutterActivity launches don't
     * crash with "requested cached FlutterEngine did not exist."
     */
    private fun ensureEngineReady() {
        if (!initialized) {
            throw Exception("Crowdplay SDK is not initialized. Please call initialize before calling this function.")
        }
        val cacheMissing = FlutterEngineCache.getInstance().get(ENGINE_CACHE_KEY) == null
        val engineMissing = flutterEngine == null
        if (!cacheMissing && !engineMissing) return

        // Prefer appContext; fall back to lastActivityContext's application
        // context so the rebuilt engine doesn't retain an Activity.
        val ctx = appContext ?: lastActivityContext?.applicationContext
        if (ctx == null) {
            throw IllegalStateException(
                "Crowdplay SDK lost its FlutterEngine and has no context to rebuild it. " +
                "The host must call CrowdplaySdk.initialize(...) again."
            )
        }
        buildAndCacheEngine(ctx)
    }

    /**
     * Public entry point for "make CrowdPlay visible now." Routes through
     * onPresentRequested if the host has set one (embedded integration);
     * otherwise launches the takeover activity (default fullscreen).
     */
    fun startActivity() {
        requestPresent()
    }

    /**
     * Single funnel for all SDK-initiated "show me now" requests
     * (direct startActivity, notification arrival, deep-link arrival).
     * Hands off to onPresentRequested when set; auto-presents otherwise.
     */
    private fun requestPresent() {
        ensureEngineReady()
        val handler = onPresentRequested
        if (handler != null) {
            handler()
            return
        }
        val intent = FlutterActivity
            .withCachedEngine(ENGINE_CACHE_KEY)
            .build(lastActivityContext!!)
        lastActivityContext!!.startActivityForResult(intent, childActivityId);
    }

    /**
     * Returns the SDK's FlutterFragment, lazily creating it on first call and
     * reusing the same instance thereafter. Only one FlutterFragment may be
     * attached to the cached engine at a time — do not render this fragment
     * in two places concurrently. Call resetFragment() if you need a fresh
     * instance (e.g. after fully tearing down the host that owned it).
     */
    fun getFragment(): FlutterFragment {
        ensureEngineReady()
        val existing = cachedFragment
        if (existing != null) {
            return existing
        }
        val fragment = FlutterFragment.withCachedEngine(ENGINE_CACHE_KEY).build<FlutterFragment>()
        cachedFragment = fragment
        return fragment
    }

    /**
     * Discards the cached FlutterFragment so the next getFragment() call
     * builds a fresh one. The underlying cached Flutter engine is retained.
     */
    fun resetFragment() {
        cachedFragment = null
    }

    fun handleNotificationActivation(bundle : Bundle?) : Boolean {
        if (channel == null ||
            bundle == null || bundle.getString("source") != "crowdplay"
        ) {
            return false;
        }

        requestPresent();

        mainHandler.postDelayed({
            channel?.invokeMethod("handleNotification", bundle.toMap())
        }, 1000L)
        return true;
    }

    fun handleNotification(remoteMessage: RemoteMessage?): Boolean {
        if (channel == null ||
            remoteMessage == null || remoteMessage.data.getOrDefault("source", null) == null ||
            remoteMessage.data.getOrDefault("source", null) != "crowdplay"
        ) {
            return false;
        }

        requestPresent();

        mainHandler.postDelayed({
            channel?.invokeMethod("handleNotification", remoteMessage.data)
        }, 1000L)
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
                        mainHandler.post { result.success(freshToken) }
                    }.exceptionally { _ ->
                        mainHandler.post { result.success(null) }
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
            "onLoyaltyCodeChanged" -> {
                val args = call.arguments as? Map<*, *>
                onLoyaltyCodeChanged?.invoke(
                    args?.get("code") as? String,
                    args?.get("description") as? String
                )
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
        // No engine means no more rotations, so the host's rendered code is
        // already stale: clear it rather than leave a dead code on screen.
        onLoyaltyCodeChanged?.invoke(null, null)
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
        // TODO: Remove rtl-callback once deployed RTL producers use rtl-sdk/callback
        // and legacy links and in-flight callbacks have been retired.
        if (flutterEngine == null ||
            (appLink.host != "crowdplay" &&
             appLink.host != "rtl-sdk" &&
             appLink.host != "rtl-callback")) {
            return false;
        }

        channel!!.invokeMethod("handleAppLink", appLink.toString());

        mainHandler.postDelayed({
            requestPresent()
        }, 300L)
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

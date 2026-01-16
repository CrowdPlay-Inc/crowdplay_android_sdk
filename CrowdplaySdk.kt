package com.crowdplay;

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import com.google.firebase.messaging.RemoteMessage
import io.flutter.embedding.android.FlutterActivity
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
import java.lang.ref.WeakReference
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

    var flutterEngine: FlutterEngine? = null
        private set
    private var channel: MethodChannel? = null
    private var application: Application? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    private var childActivityId: Int = 98374897
    private val ENGINE_ID = "flutter_activity_id"

    var showVenueNextWalletHandler: (() -> Unit)? = null

    private var pointsResult: CompletableFuture<Int?> = CompletableFuture<Int?>();

    private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            currentActivityRef = WeakReference(activity)
        }
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            // Clear reference if it's the destroyed activity
            if (currentActivityRef?.get() === activity) {
                currentActivityRef = null
            }
        }
    }

    companion object {
        private var instance: CrowdplaySdk? = null
        fun getInstance(): CrowdplaySdk {
            if (instance == null) {
                instance = CrowdplaySdk()
            }
            return instance!!
        }
    }

    @Synchronized
    private fun ensureEngine() {
        val app = application
            ?: throw IllegalStateException("SDK not initialized. Call initialize() first.")

        // If engine is cached, we're good
        val cached = FlutterEngineCache.getInstance().get(ENGINE_ID)
        if (cached != null) {
            flutterEngine = cached
            return
        }

        // Otherwise (process death, race, etc.), recreate and re-cache
        val engine = FlutterEngine(app).also { fe ->
            fe.plugins.add(this)
            fe.navigationChannel.setInitialRoute("/")
            fe.dartExecutor.executeDartEntrypoint(
                DartExecutor.DartEntrypoint.createDefault()
            )
        }

        flutterEngine = engine
        FlutterEngineCache.getInstance().put(ENGINE_ID, engine)
    }

    fun initialize(apiKey: String, appUrlScheme: String, context: Activity) {
        this.apiKey = apiKey
        this.appUrlScheme = appUrlScheme
        this.application = context.application
        this.currentActivityRef = WeakReference(context)

        // Register lifecycle callbacks to track current activity
        context.application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)

        ensureEngine()
    }

    private val currentActivity: Activity?
        get() = currentActivityRef?.get()

    fun startActivity(activity: Activity? = null) {
        val context = activity ?: currentActivity
            ?: throw IllegalStateException("No activity context available. Ensure an activity is in the foreground or pass one explicitly.")

        ensureEngine()

        val intent = FlutterActivity.withCachedEngine(ENGINE_ID).build(context)
        context.startActivityForResult(intent, childActivityId)
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
                // The current activity should be the Flutter activity when this is called
                val activity = currentActivity
                if (activity is FlutterActivity) {
                    activity.finish()
                }
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

    fun setAuthToken(authToken: String, provider: String) {
        this.authToken["provider"] = provider
        this.authToken["token"] = authToken

        if (flutterEngine != null) {
            channel!!.invokeMethod("performTokenLogin", this.authToken);
        }
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

package it.airgap.vault.plugin.securityutils

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.view.WindowManager
import com.getcapacitor.NativePlugin
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.scottyab.rootbeer.RootBeer
import it.airgap.vault.BuildConfig
import it.airgap.vault.plugin.PluginError
import it.airgap.vault.plugin.securityutils.SecurityUtils.Companion.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS
import it.airgap.vault.plugin.securityutils.storage.Storage
import it.airgap.vault.util.assertReceived
import it.airgap.vault.util.freeCallIfSaved
import it.airgap.vault.util.logDebug
import it.airgap.vault.util.resolveWithData
import java.util.*

@NativePlugin(
        requestCodes = [REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS]
)
class SecurityUtils : Plugin() {

    private val keyguardManager: KeyguardManager? by lazy {
        activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }

    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences("ch.airgap.securityutils", Context.MODE_PRIVATE)
    }

    private var onAuthSuccess: (() -> Unit)? = null
    private var onAuthFailure: (() -> Unit)? = null

    private var isAuthenticated: Boolean = false
    private var lastBackgroundDate: Date? = null

    private var automaticLocalAuthentication: Boolean
        get() = sharedPreferences.getBoolean(PREFERENCES_KEY_AUTOMATIC_AUTHENTICATION, false)
        set(value) { sharedPreferences.edit().putBoolean(PREFERENCES_KEY_AUTOMATIC_AUTHENTICATION, value).apply() }

    private var invalidateAfterSeconds: Int = 10

    private val needsAuthentication: Boolean
        get() {
            lastBackgroundDate?.let { isAuthenticated = !it.exceededTimeout }
            return !isAuthenticated
        }

    private var authTries: Int = 0
        @Synchronized get
        @Synchronized set

    private val isDebuggable: Boolean
        get() {
            return BuildConfig.DEBUG || (context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }

    private val integrityAssessment: Boolean
        get() {
            return !RootBeer(context).isRootedWithoutBusyBoxCheck || isDebuggable || BuildConfig.APPIUM
        }

    /*
     * SecureStorage
     */

    @PluginMethod
    fun initStorage(call: PluginCall) {
        saveCall(call)
        with (call) {
            try {
                assertReceived(Param.ALIAS, Param.IS_PARANOIA)
                if (isParanoia) {
                    setupParanoiaPassword(call)
                } else {
                    freeCallIfSaved()
                    resolve()
                }
            } catch (e: Exception) {
                freeCallIfSaved()
                reject(e.toString())
            }
        }
    }

    @PluginMethod
    fun isDeviceSecure(call: PluginCall) {
        val isDeviceSecure = (if (keyguardManager?.isKeyguardSecure == true) 1 else 0)
        call.resolveWithData(Key.VALUE to isDeviceSecure)
    }

    @PluginMethod
    fun secureDevice(call: PluginCall) {
        val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
        activity.startActivity(intent)

        call.resolve()
    }

    @PluginMethod
    fun getItem(call: PluginCall) {
        saveCall(call)
        with (call) {
            try {
                assessIntegrity()
                assertReceived(Param.ALIAS, Param.IS_PARANOIA, Param.FILE_KEY)

                Storage(context, alias, isParanoia).readString(key, {
                    logDebug("getItem: success")
                    freeCallIfSaved()
                    resolveWithData(Key.VALUE to it)
                }, {
                    logDebug("getItem: failure")
                    freeCallIfSaved()
                    reject(it.toString())
                }, createAuthenticationRequestedHandler(call))
            } catch (e: Exception) {
                freeCallIfSaved()
                reject(e.toString())
            }
        }
    }

    @PluginMethod
    fun setItem(call: PluginCall) {
        saveCall(call)
        with (call) {
            try {
                assessIntegrity()
                assertReceived(Param.ALIAS, Param.IS_PARANOIA, Param.FILE_KEY, Param.VALUE)

                Storage(context, alias, isParanoia).writeString(key, value, {
                    logDebug("setItem: success")
                    freeCallIfSaved()
                    resolve()
                }, {
                    logDebug("setItem: failure")
                    freeCallIfSaved()
                    reject(it.toString())
                }, createAuthenticationRequestedHandler(call))
            } catch (e: Exception) {
                freeCallIfSaved()
                reject(e.toString())
            }
        }
    }

    @PluginMethod
    fun removeAll(call: PluginCall) {
        saveCall(call)
        with (call) {
            try {
                assertReceived(Param.ALIAS)

                val result = Storage.removeAll(activity, alias)
                if (result) {
                    freeCallIfSaved()
                    resolve()
                } else {
                    freeCallIfSaved()
                    reject("removeAll: failure")
                }
            } catch (e: Exception) {
                freeCallIfSaved()
                reject(e.toString())
            }
        }
    }

    @PluginMethod
    fun removeItem(call: PluginCall) {
        saveCall(call)
        with (call) {
            try {
                assertReceived(Param.ALIAS, Param.IS_PARANOIA, Param.FILE_KEY)

                Storage(context, alias, isParanoia).removeString(key, {
                    logDebug("delete: success")
                    freeCallIfSaved()
                    resolve()
                }, {
                    logDebug("delete: failure")
                    freeCallIfSaved()
                    reject(it.toString())
                })
            } catch (e: Exception) {
                freeCallIfSaved()
                reject(e.toString())
            }
        }
    }

    @PluginMethod
    fun destroy(call: PluginCall) {
        saveCall(call)
        with (call) {
            try {
                val result = Storage.destroy(activity)
                if (result) {
                    freeCallIfSaved()
                    resolve()
                } else {
                    freeCallIfSaved()
                    reject("destroy: failure")
                }
            } catch (e: Exception) {
                freeCallIfSaved()
                reject(e.toString())
            }
        }
    }

    @PluginMethod
    fun setupParanoiaPassword(call: PluginCall) {
        saveCall(call)
        with (call) {
            try {
                assertReceived(Param.ALIAS, Param.IS_PARANOIA)

                Storage(context, alias, isParanoia).setupParanoiaPassword({
                    logDebug("paranoia setup: success")
                    freeCallIfSaved()
                    resolve()
                }, {
                    logDebug("paranoia setup: failure")
                    freeCallIfSaved()
                    reject(it.toString())
                })
            } catch (e: Exception) {
                freeCallIfSaved()
                reject(e.toString())
            }
        }
    }

    @PluginMethod
    fun setupRecoveryPassword(call: PluginCall) {
        saveCall(call)
        with (call) {
            try {
                assertReceived(Param.ALIAS, Param.IS_PARANOIA, Param.FILE_KEY, Param.VALUE)

                Storage(context, alias, isParanoia).writeRecoverableString(key, value, {
                    logDebug("written recoverable: success")
                    freeCallIfSaved()
                    resolveWithData("recoveryKey" to it)
                }, {
                    logDebug("written recoverable: failure")
                    freeCallIfSaved()
                    reject(it.toString())
                }, createAuthenticationRequestedHandler(call))
            } catch (e: Exception) {
                freeCallIfSaved()
                reject(e.toString())
            }
        }
    }

    /*
     * LocalAuthentication
     */

    @PluginMethod
    fun authenticate(call: PluginCall) {
        authenticateOrContinue(AuthOrigin.VAULT, call, { call.resolve() }, { call.reject("Authentication failed") })
    }

    @PluginMethod
    fun setInvalidationTimeout(call: PluginCall) {
        with (call) {
            assertReceived(Param.TIMEOUT)
            invalidateAfterSeconds = timeout
            resolve()
        }
    }

    @PluginMethod
    fun invalidate(call: PluginCall) {
        isAuthenticated = false
        lastBackgroundDate = null
        call.resolve()
    }

    @PluginMethod
    fun toggleAutomaticAuthentication(call: PluginCall) {
        with (call) {
            assertReceived(Param.AUTOMATIC_AUTHENTICATION)
            automaticLocalAuthentication = automaticAuthentication
            resolve()
        }
    }

    @PluginMethod
    fun setAuthenticationReason(call: PluginCall) {
        call.resolve()
    }

    /*
     * DeviceIntegrity
     */

    @PluginMethod
    fun assessDeviceIntegrity(call: PluginCall) {
        call.resolveWithData(Key.VALUE to integrityAssessment)
    }

    /*
     * Secure Screen
     */

    @PluginMethod
    fun waitForOverlayDismiss(call: PluginCall) {
        call.resolve()
    }

    @PluginMethod
    fun setWindowSecureFlag(call: PluginCall) {
        if(!BuildConfig.APPIUM){
            with (activity) {
                runOnUiThread { window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE) }
            }
        }
        call.resolve()
    }

    @PluginMethod
    fun clearWindowSecureFlag(call: PluginCall) {
        if(!BuildConfig.APPIUM){
            with (activity) {
                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            }
        }
        call.resolve()
    }

    override fun handleOnResume() {
        super.handleOnResume()
        if (automaticLocalAuthentication) {
            authenticateOrContinue(AuthOrigin.VAULT, savedCall, { freeCallIfSaved() })
        }
    }

    override fun handleOnPause() {
        lastBackgroundDate = Date()
        super.handleOnPause()
    }

    override fun handleOnActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.handleOnActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS -> {
                val callback = if (resultCode == Activity.RESULT_OK) onAuthSuccess else onAuthFailure
                callback?.invoke()

                onAuthSuccess = null
                onAuthFailure = null
            }
        }
    }

    private fun showAuthenticationScreen(call: PluginCall? = null, onAuthenticated: (() -> Unit)? = null, onFailure: (() -> Unit)? = null) {
        val intent = keyguardManager?.createConfirmDeviceCredentialIntent(null, null)
        if (intent != null) {
            onAuthSuccess = onAuthenticated
            onAuthFailure = onFailure
            startActivityForResult(call, intent, REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS)
        }
    }

    private fun createAuthenticationRequestedHandler(call: PluginCall): (Int, () -> Unit) -> Unit =
        { attemptNo, onAuthenticated ->
            authenticate(AuthOrigin.SYSTEM, attemptNo, call, onAuthenticated)
        }

    private fun authenticateOrContinue(origin: AuthOrigin, call: PluginCall? = null, onAuthenticated: (() -> Unit)? = null, onFailure: (() -> Unit)? = null) {
        if (!needsAuthentication) {
            onAuthenticated?.invoke()
        } else {
            authenticate(
                    origin,
                    ++authTries,
                    call,
                    {
                        onAuthenticated?.invoke()
                        authTries = 0
                    },
                    onFailure
            )
        }
    }

    private fun authenticate(
            origin: AuthOrigin,
            attemptNo: Int,
            call: PluginCall? = null,
            onAuthenticated: (() -> Unit)? = null,
            onFailure: (() -> Unit)? = null
    ) {
        if (call != null && attemptNo > MAX_AUTH_TRIES) {
            val error = when (origin) {
                AuthOrigin.VAULT -> PluginError.maxAuthenticationRetriesVault
                AuthOrigin.SYSTEM -> PluginError.maxAuthenticationRetriesSystem
            }
            call.reject(error.toString())
            return
        }

        showAuthenticationScreen(call, onAuthenticated = {
            isAuthenticated = true
            lastBackgroundDate = null
            onAuthenticated?.invoke()
        }, onFailure = {
            isAuthenticated = false
            lastBackgroundDate = null
            onFailure?.invoke()
        })
    }

    private val PluginCall.alias: String
        get() = getString(Param.ALIAS)

    private val PluginCall.isParanoia: Boolean
        get() = getBoolean(Param.IS_PARANOIA)

    private val PluginCall.key: String
        get() = getString(Param.FILE_KEY)

    private val PluginCall.value: String
        get() = getString(Param.VALUE)

    private val PluginCall.timeout: Int
        get() = getInt(Param.TIMEOUT)

    private val PluginCall.automaticAuthentication: Boolean
        get() = getBoolean(Param.AUTOMATIC_AUTHENTICATION)

    private fun PluginCall.assessIntegrity() {
        if (!integrityAssessment) {
            reject("Invalid state")
        }
    }

    private val Date.exceededTimeout: Boolean
        get() = time + (invalidateAfterSeconds * 1000L) <= Date().time

    private object Param {
        const val ALIAS = "alias"
        const val IS_PARANOIA = "isParanoia"
        const val FILE_KEY = "key"
        const val VALUE = "value"

        const val TIMEOUT = "timeout"
        const val AUTOMATIC_AUTHENTICATION = "automatic"
    }

    private object Key {
        const val VALUE = "value"
    }

    companion object {
        const val REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS = 1

        private const val PREFERENCES_KEY_AUTOMATIC_AUTHENTICATION = "autoauth"
        private const val MAX_AUTH_TRIES = 3
    }
}

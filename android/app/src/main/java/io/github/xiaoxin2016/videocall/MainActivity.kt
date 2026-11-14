package io.github.xiaoxin2016.videocall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.xiaoxin2016.videocall.databinding.ActivityMainBinding
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Bluetooth is optional (headset routing); camera + mic are required.
            if (REQUIRED.all { p -> granted(p) }) startCall()
            else Toast.makeText(this, R.string.err_permissions, Toast.LENGTH_LONG).show()
        }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.inputHost.setText(prefs.getString("host", ""))
        b.inputPort.setText(prefs.getString("port", "8443"))
        b.inputName.setText(prefs.getString("name", Build.MODEL))
        b.checkRemember.isChecked = prefs.getBoolean("remember", false)
        if (b.checkRemember.isChecked) {
            prefs.getString("password_enc", null)?.let { enc ->
                runCatching { SecretBox.decrypt(enc) }.getOrNull()?.let { b.inputPassword.setText(it) }
            }
        }
        b.versionText.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)

        b.btnCall.setOnClickListener { onCallClicked() }
        b.btnClearPins.setOnClickListener {
            val n = PinStore(this).count()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_pins)
                .setMessage(getString(R.string.clear_pins_confirm, n))
                .setPositiveButton(R.string.ok) { _, _ ->
                    PinStore(this).clearAll()
                    Toast.makeText(this, R.string.pins_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun onCallClicked() {
        val host = normalizeHost(b.inputHost.text.toString())
        val port = b.inputPort.text.toString().trim().toIntOrNull()
        if (host.isEmpty()) {
            b.inputHost.error = getString(R.string.err_host)
            return
        }
        if (port == null || port !in 1..65535) {
            b.inputPort.error = getString(R.string.err_port)
            return
        }
        b.inputHost.setText(host)

        val remember = b.checkRemember.isChecked
        val editor = prefs.edit()
            .putString("host", host)
            .putString("port", port.toString())
            .putString("name", b.inputName.text.toString().trim())
            .putBoolean("remember", remember)
        if (remember) {
            runCatching { SecretBox.encrypt(b.inputPassword.text.toString()) }
                .onSuccess { editor.putString("password_enc", it) }
        } else {
            editor.remove("password_enc")
        }
        editor.apply()

        val wanted = REQUIRED + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }
        val missing = wanted.filterNot(::granted)
        if (missing.isEmpty()) startCall() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startCall() {
        val host = b.inputHost.text.toString()
        val port = b.inputPort.text.toString().trim().toInt()
        val name = b.inputName.text.toString().trim().ifEmpty { Build.MODEL }
        startActivity(CallActivity.intent(this, host, port, b.inputPassword.text.toString(), name))
    }

    /** Accepts "host", "https://host/", "[v6]" etc. and returns the bare host. */
    private fun normalizeHost(raw: String): String {
        var h = raw.trim()
        h = h.removePrefix("https://").removePrefix("http://").removePrefix("wss://")
        h = h.substringBefore('/')
        if (h.startsWith("[") && h.contains("]")) return h.substring(1, h.indexOf(']'))
        // "host:port" typed into the host field — keep only the host part
        if (h.count { it == ':' } == 1) {
            val p = h.substringAfter(':').toIntOrNull()
            if (p != null) b.inputPort.setText(p.toString())
            h = h.substringBefore(':')
        }
        return h
    }

    companion object {
        private val REQUIRED = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}

/** Encrypts the remembered password with an AES-GCM key held in the Android Keystore. */
private object SecretBox {
    private const val ALIAS = "videocall_password_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String {
        val data = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }
}

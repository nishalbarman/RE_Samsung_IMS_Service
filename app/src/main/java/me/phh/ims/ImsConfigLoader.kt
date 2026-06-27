//SPDX-License-Identifier: GPL-2.0
package me.phh.ims

import android.content.Context
import android.telephony.Rlog
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class CarrierSwitchConfig(
    val enableIms: Boolean = false,
    val enableServiceVolte: Boolean = false,
    val enableServiceVowifi: Boolean = false,
    val enableServiceVilte: Boolean = false,
    val enableServiceSmsip: Boolean = false,
    val enableServiceRcs: Boolean = false,
    val enableServiceRcschat: Boolean = false,
)

data class CarrierProfileConfig(
    val name: String = "",
    val mnoname: String = "",
    val transport: String? = null,
    val smscSet: String? = null,
    val authAlgo: String? = null,
    val supportIpsec: Boolean = false,
    val pcscfPref: Int = 0,
    val ipver: String? = null,
    val pdn: String? = null,
    val timer: String? = null,
)

data class CarrierConfig(
    val mnoname: String,
    val switchConfig: CarrierSwitchConfig,
    val profiles: List<CarrierProfileConfig>,
)

class ImsConfigLoader(private val ctxt: Context) {
    companion object {
        private const val TAG = "PHH ImsConfigLoader"
    }

    private val mccMncToMnoname = HashMap<String, String>()
    private val switchConfigs = HashMap<String, CarrierSwitchConfig>()
    private val profileConfigs = HashMap<String, MutableList<CarrierProfileConfig>>()
    private var loaded = false

    fun load() {
        if (loaded) return
        try {
            loadMnomap()
            loadSwitchConfig()
            loadProfileConfig()
            loaded = true
            Rlog.d(TAG, "Loaded carrier config: ${mccMncToMnoname.size} MCC/MNC mappings, ${switchConfigs.size} switch configs, ${profileConfigs.size} profile configs")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load carrier config", t)
        }
    }

    private fun loadMnomap() {
        val input = ctxt.resources.openRawResource(
            ctxt.resources.getIdentifier("mnomap", "raw", ctxt.packageName)
        )
        val text = input.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val arr = json.getJSONArray("mnomap")
        for (i in 0 until arr.length()) {
            val entry = arr.getJSONObject(i)
            val mccmnc = entry.getString("mccmnc")
            val mnoname = entry.getString("mnoname")
            mccMncToMnoname[mccmnc] = mnoname
        }
    }

    private fun loadSwitchConfig() {
        val input = ctxt.resources.openRawResource(
            ctxt.resources.getIdentifier("imsswitch", "raw", ctxt.packageName)
        )
        val text = input.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val arr = json.getJSONArray("imsswitch")
        for (i in 0 until arr.length()) {
            val entry = arr.getJSONObject(i)
            val mnoname = entry.getString("mnoname")
            val cfg = CarrierSwitchConfig(
                enableIms = entry.optBoolean("enableIms", false),
                enableServiceVolte = entry.optBoolean("enableServiceVolte", false),
                enableServiceVowifi = entry.optBoolean("enableServiceVowifi", false),
                enableServiceVilte = entry.optBoolean("enableServiceVilte", false),
                enableServiceSmsip = entry.optBoolean("enableServiceSmsip", false),
                enableServiceRcs = entry.optBoolean("enableServiceRcs", false),
                enableServiceRcschat = entry.optBoolean("enableServiceRcschat", false),
            )
            switchConfigs[mnoname] = cfg
        }
    }

    private fun loadProfileConfig() {
        val input = ctxt.resources.openRawResource(
            ctxt.resources.getIdentifier("imsprofile", "raw", ctxt.packageName)
        )
        val text = input.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val arr = json.getJSONArray("profile")
        for (i in 0 until arr.length()) {
            val entry = arr.getJSONObject(i)
            val mnoname = entry.optString("mnoname", "")
            if (mnoname.isEmpty() || mnoname == "DEFAULT") continue
            val cfg = CarrierProfileConfig(
                name = entry.optString("name", ""),
                mnoname = mnoname,
                transport = entry.optString("transport", "").ifEmpty { null },
                smscSet = entry.optString("smsc_set", "").ifEmpty { null },
                authAlgo = entry.optString("auth_algo", "").ifEmpty { null },
                supportIpsec = entry.optBoolean("support_ipsec", false),
                pcscfPref = entry.optInt("pcscf_pref", 0),
                ipver = entry.optString("ipver", "").ifEmpty { null },
                pdn = entry.optString("pdn", "").ifEmpty { null },
                timer = entry.optString("timer", "").ifEmpty { null },
            )
            profileConfigs.getOrPut(mnoname) { mutableListOf() }.add(cfg)
        }
    }

    fun getMnoname(mccmnc: String): String? {
        load()
        val raw = mccMncToMnoname[mccmnc]
        return raw?.removeSuffix("@BLOCKGC")
    }

    fun getSwitchConfig(mnoname: String): CarrierSwitchConfig? {
        load()
        return switchConfigs[mnoname]
    }

    fun getProfiles(mnoname: String): List<CarrierProfileConfig> {
        load()
        return profileConfigs[mnoname] ?: emptyList()
    }

    fun getFirstVolteProfile(mnoname: String): CarrierProfileConfig? {
        return getProfiles(mnoname).firstOrNull { it.name.contains("VoLTE", ignoreCase = true) }
    }

    fun isControlSocketUdp(mccmnc: String): Boolean {
        val mnoname = getMnoname(mccmnc) ?: return false
        val profile = getFirstVolteProfile(mnoname) ?: return false
        val transport = profile.transport ?: return false
        return transport.contains("udp", ignoreCase = true)
    }

    fun forceSmsc(mccmnc: String): String? {
        val mnoname = getMnoname(mccmnc) ?: return null
        val profile = getFirstVolteProfile(mnoname) ?: return null
        return profile.smscSet?.takeIf { it.isNotEmpty() }
    }

    fun requireNonsessAka(mccmnc: String): Boolean {
        val mnoname = getMnoname(mccmnc) ?: return false
        val profile = getFirstVolteProfile(mnoname) ?: return false
        return profile.authAlgo == null || profile.authAlgo.contains("md5", ignoreCase = true)
    }
}

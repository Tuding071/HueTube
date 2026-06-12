// ═══════════════════════════════════════════════════════════════════
// HueTube - V1.5 (Ad Blocker + Sliding Bottom Sheet)
// ═══════════════════════════════════════════════════════════════════
// === PART 0/10 — Theme Specification ===
// ═══════════════════════════════════════════════════════════════════
//
// THEME: Dark YouTube — Near-Black Background
//
// Fullscreen: website requests orientation → SENSOR allows both portrait
//             and landscape → orientation saved before entry, restored on exit.
//             No Activity recreation (configChanges handles it).
//
// Back navigation:
//   FULLSCREEN → exit fullscreen via callback + restore orientation
//   canGoBack  → goBack()
//   on homepage → exit app
//   elsewhere  → go to homepage
//
// Ad Blocker:
//   - Toggle (enabled by default) in bottom sheet
//   - Update button fetches uBlock filter txt from GitHub
//   - Parses youtube.com rules: scriptlets, cosmetic, network
//   - Scriptlet engine: bundled JS implementations, rules drive args
//   - Cosmetic: CSS injection via <style> tag
//   - Network: shouldInterceptRequest blocks matched URLs
//   - Rules saved to app private storage, loaded on each page
//
// ═══════════════════════════════════════════════════════════════════


// ═══════════════════════════════════════════════════════════════════
// === PART 1/10 — Package, Imports, MainActivity ===
// ═══════════════════════════════════════════════════════════════════

package com.huetube.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HueTubeApp() }
    }
}

// END OF PART 1/10


// ═══════════════════════════════════════════════════════════════════
// === PART 2/10 — Constants ===
// ═══════════════════════════════════════════════════════════════════

private val BG         = Color(0xFF0A0A0A)
private val SHEET_BG   = Color(0xFF141414)
private val ACCENT     = Color(0xFFFF0000)
private val TEXT_PRI   = Color(0xFFEEEEEE)
private val TEXT_SEC   = Color(0xFF888888)
private val DIVIDER    = Color(0xFF222222)

private const val HOMEPAGE_URL   = "https://m.youtube.com"
private const val RULES_FILENAME = "yt_adblocker_rules.json"

// uBlock core filters — primary source for youtube.com rules
private const val UBLOCK_FILTERS_URL =
    "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt"

// END OF PART 2/10


// ═══════════════════════════════════════════════════════════════════
// === PART 3/10 — Fullscreen Manager ===
// ═══════════════════════════════════════════════════════════════════

class FullscreenManager(val activity: android.app.Activity) {

    private var customView: View? = null
    private var webViewContainer: FrameLayout? = null
    private var webView: WebView? = null
    private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var isActive: Boolean = false

    fun enterFullscreen(
        view: View,
        callback: WebChromeClient.CustomViewCallback,
        container: FrameLayout,
        wv: WebView
    ) {
        if (isActive) return
        isActive = true

        customView = view
        webViewContainer = container
        webView = wv
        savedOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        container.removeView(wv)

        val decorView = activity.window.decorView as android.widget.FrameLayout
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        decorView.addView(view)

        decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    fun exitFullscreen(callback: WebChromeClient.CustomViewCallback?) {
        if (!isActive) return
        isActive = false

        val decorView = activity.window.decorView as android.widget.FrameLayout
        customView?.let { decorView.removeView(it) }
        customView = null

        webView?.let { wv ->
            webViewContainer?.let { container ->
                if (wv.parent == null) container.addView(wv)
            }
        }

        decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        activity.requestedOrientation = savedOrientation
        callback?.onCustomViewHidden()

        webViewContainer = null
        webView = null
    }
}

// END OF PART 3/10


// ═══════════════════════════════════════════════════════════════════
// === PART 4/10 — Parsed Rules Data Model ===
// ═══════════════════════════════════════════════════════════════════
//
// ParsedRules holds three categories extracted from the filter txt:
//   scriptlets  — list of ScriptletRule(name, args)
//   cosmetic    — list of CSS selector strings
//   network     — list of URL substring patterns to block
//
// Serialized as JSON to RULES_FILENAME in app files dir.
// Loaded at startup; refreshed when user taps Update.
//
// ═══════════════════════════════════════════════════════════════════

data class ScriptletRule(val name: String, val args: String)

data class ParsedRules(
    val scriptlets: List<ScriptletRule> = emptyList(),
    val cosmetic:   List<String>        = emptyList(),
    val network:    List<String>        = emptyList(),
    val updatedAt:  String              = ""
)

// Simple JSON serializer — no Gson dependency needed
fun ParsedRules.toJson(): String {
    val sb = StringBuilder()
    sb.append("{")
    sb.append("\"updatedAt\":\"${updatedAt}\",")
    sb.append("\"scriptlets\":[")
    scriptlets.forEachIndexed { i, r ->
        if (i > 0) sb.append(",")
        sb.append("{\"name\":\"${r.name.escJ()}\",\"args\":\"${r.args.escJ()}\"}")
    }
    sb.append("],")
    sb.append("\"cosmetic\":[")
    cosmetic.forEachIndexed { i, s ->
        if (i > 0) sb.append(",")
        sb.append("\"${s.escJ()}\"")
    }
    sb.append("],")
    sb.append("\"network\":[")
    network.forEachIndexed { i, s ->
        if (i > 0) sb.append(",")
        sb.append("\"${s.escJ()}\"")
    }
    sb.append("]}")
    return sb.toString()
}

private fun String.escJ() = replace("\\", "\\\\").replace("\"", "\\\"")

fun parseJsonRules(json: String): ParsedRules {
    return try {
        val updatedAt  = Regex("\"updatedAt\":\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
        val scriptlets = Regex("\\{\"name\":\"([^\"]*)\",\"args\":\"([^\"]*)\"\\}")
            .findAll(json).map { ScriptletRule(it.groupValues[1].unescJ(), it.groupValues[2].unescJ()) }.toList()
        val cosmetic   = Regex("\"cosmetic\":\\[([^\\]]*)]").find(json)?.groupValues?.get(1)
            ?.let { Regex("\"([^\"]*)\"").findAll(it).map { m -> m.groupValues[1].unescJ() }.toList() } ?: emptyList()
        val network    = Regex("\"network\":\\[([^\\]]*)]").find(json)?.groupValues?.get(1)
            ?.let { Regex("\"([^\"]*)\"").findAll(it).map { m -> m.groupValues[1].unescJ() }.toList() } ?: emptyList()
        ParsedRules(scriptlets, cosmetic, network, updatedAt)
    } catch (e: Exception) {
        ParsedRules()
    }
}

private fun String.unescJ() = replace("\\\"", "\"").replace("\\\\", "\\")

// END OF PART 4/10


// ═══════════════════════════════════════════════════════════════════
// === PART 5/10 — Scriptlet Library ===
// ═══════════════════════════════════════════════════════════════════
//
// Bundled JS implementations for the scriptlets YouTube actually uses.
// Keys match the names found in uBlock filter rules after ##+js(
//
// Implemented:
//   json-prune            — strips keys from fetch/XHR JSON responses
//   set-constant          — forces a property to a fixed value
//   abort-on-property-read — throws when JS reads a property
//   abort-on-property-write— throws when JS writes a property
//   no-xhr-if             — blocks XHR requests matching a pattern
//   no-fetch-if           — blocks fetch requests matching a pattern
//   addEventListener-defuser — prevents specific event listeners
//
// ═══════════════════════════════════════════════════════════════════

object ScriptletLibrary {

    // json-prune: intercept fetch+XHR, remove specified keys from JSON responses
    // args format: "key1 key2 key3"
    private val JSON_PRUNE = """
(function(args) {
    var keys = args.trim().split(/\s+/).filter(Boolean);
    if (!keys.length) return;
    function pruneObj(obj) {
        if (!obj || typeof obj !== 'object') return obj;
        keys.forEach(function(k) {
            var parts = k.split('.');
            var cur = obj;
            for (var i = 0; i < parts.length - 1; i++) {
                if (!cur || typeof cur !== 'object') return;
                cur = cur[parts[i]];
            }
            if (cur && typeof cur === 'object') {
                delete cur[parts[parts.length - 1]];
            }
        });
        return obj;
    }
    function tryPrune(text) {
        try {
            var obj = JSON.parse(text);
            pruneObj(obj);
            return JSON.stringify(obj);
        } catch(e) { return text; }
    }
    // Intercept fetch
    var origFetch = window.fetch;
    window.fetch = function() {
        return origFetch.apply(this, arguments).then(function(resp) {
            var ct = resp.headers.get('content-type') || '';
            if (!ct.includes('json')) return resp;
            return resp.text().then(function(text) {
                var pruned = tryPrune(text);
                return new Response(pruned, { status: resp.status, headers: resp.headers });
            });
        });
    };
    // Intercept XHR
    var origOpen = XMLHttpRequest.prototype.open;
    var origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function() {
        var xhr = this;
        var origOnReadyStateChange = xhr.onreadystatechange;
        Object.defineProperty(xhr, 'responseText', {
            get: function() {
                var raw = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'responseText');
                var text = raw ? raw.get.call(xhr) : '';
                return tryPrune(text);
            },
            configurable: true
        });
        origSend.apply(this, arguments);
    };
})("ARGS");
""".trimIndent()

    // set-constant: force window.PROP = VALUE
    // args format: "property value"
    private val SET_CONSTANT = """
(function(args) {
    var parts = args.trim().match(/^(\S+)\s+(.+)$/);
    if (!parts) return;
    var prop = parts[1], val = parts[2];
    var value;
    if (val === 'true')       value = true;
    else if (val === 'false') value = false;
    else if (val === 'null')  value = null;
    else if (val === 'undefined') value = undefined;
    else if (val === "''")    value = '';
    else if (!isNaN(val))     value = Number(val);
    else                      value = val;
    var chain = prop.split('.');
    function setDeep(obj, parts, v) {
        if (parts.length === 1) {
            try { Object.defineProperty(obj, parts[0], { get: function(){ return v; }, set: function(){}, configurable: false }); } catch(e) {}
            return;
        }
        if (!obj[parts[0]]) obj[parts[0]] = {};
        setDeep(obj[parts[0]], parts.slice(1), v);
    }
    setDeep(window, chain, value);
})("ARGS");
""".trimIndent()

    // abort-on-property-read: throw when a property is accessed
    // args format: "property"
    private val ABORT_ON_PROPERTY_READ = """
(function(args) {
    var prop = args.trim();
    if (!prop) return;
    var chain = prop.split('.');
    function trap(obj, parts) {
        if (parts.length === 1) {
            try {
                Object.defineProperty(obj, parts[0], {
                    get: function() { throw new ReferenceError('HueTube: aborted read of ' + prop); },
                    configurable: false
                });
            } catch(e) {}
            return;
        }
        var cur = obj[parts[0]];
        if (cur) trap(cur, parts.slice(1));
    }
    trap(window, chain);
})("ARGS");
""".trimIndent()

    // abort-on-property-write: throw when a property is written
    // args format: "property"
    private val ABORT_ON_PROPERTY_WRITE = """
(function(args) {
    var prop = args.trim();
    if (!prop) return;
    var chain = prop.split('.');
    function trap(obj, parts) {
        if (parts.length === 1) {
            try {
                Object.defineProperty(obj, parts[0], {
                    set: function() { throw new ReferenceError('HueTube: aborted write of ' + prop); },
                    configurable: false
                });
            } catch(e) {}
            return;
        }
        var cur = obj[parts[0]];
        if (cur) trap(cur, parts.slice(1));
    }
    trap(window, chain);
})("ARGS");
""".trimIndent()

    // no-xhr-if: block XHR requests whose URL matches pattern
    // args format: "urlPattern"
    private val NO_XHR_IF = """
(function(args) {
    var pattern = args.trim();
    if (!pattern) return;
    var re = new RegExp(pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/\\\*/g, '.*'));
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
        if (re.test(url)) {
            Object.defineProperty(this, '_blocked', { value: true });
        }
        return origOpen.apply(this, arguments);
    };
    var origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function() {
        if (this._blocked) return;
        return origSend.apply(this, arguments);
    };
})("ARGS");
""".trimIndent()

    // no-fetch-if: block fetch requests whose URL matches pattern
    // args format: "urlPattern"
    private val NO_FETCH_IF = """
(function(args) {
    var pattern = args.trim();
    if (!pattern) return;
    var re = new RegExp(pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/\\\*/g, '.*'));
    var origFetch = window.fetch;
    window.fetch = function(input, init) {
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        if (re.test(url)) return Promise.reject(new TypeError('HueTube: blocked fetch'));
        return origFetch.apply(this, arguments);
    };
})("ARGS");
""".trimIndent()

    // addEventListener-defuser: prevent specific event listeners from being added
    // args format: "eventType handlerPattern"
    private val ADD_EVENT_LISTENER_DEFUSER = """
(function(args) {
    var parts = args.trim().split(/\s+/);
    var evType = parts[0] || '';
    var handlerPat = parts[1] || '';
    var re = handlerPat ? new RegExp(handlerPat) : null;
    var origAEL = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function(type, handler) {
        if (type === evType) {
            if (!re || re.test(handler.toString())) return;
        }
        return origAEL.apply(this, arguments);
    };
})("ARGS");
""".trimIndent()

    private val IMPLEMENTATIONS = mapOf(
        "json-prune"                 to JSON_PRUNE,
        "set-constant"               to SET_CONSTANT,
        "abort-on-property-read"     to ABORT_ON_PROPERTY_READ,
        "abort-on-property-write"    to ABORT_ON_PROPERTY_WRITE,
        "no-xhr-if"                  to NO_XHR_IF,
        "no-fetch-if"                to NO_FETCH_IF,
        "addEventListener-defuser"   to ADD_EVENT_LISTENER_DEFUSER
    )

    // Build the full injection JS for a list of scriptlet rules
    fun buildInjectionJs(rules: List<ScriptletRule>): String {
        val sb = StringBuilder()
        sb.append("(function(){\n'use strict';\n")
        rules.forEach { rule ->
            val impl = IMPLEMENTATIONS[rule.name] ?: return@forEach
            // Replace ARGS placeholder with actual args, escaping for JS string safety
            val escaped = rule.args.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.append(impl.replace("\"ARGS\"", "\"$escaped\""))
            sb.append("\n")
        }
        sb.append("})();")
        return sb.toString()
    }
}

// END OF PART 5/10


// ═══════════════════════════════════════════════════════════════════
// === PART 6/10 — Filter Parser ===
// ═══════════════════════════════════════════════════════════════════
//
// Parses raw uBlock/EasyList .txt filter files.
// Extracts only rules applicable to youtube.com.
//
// Rule types parsed:
//   youtube.com##+js(name, arg1 arg2)  → ScriptletRule
//   youtube.com##.selector             → cosmetic CSS
//   ||pattern^                         → network block pattern
//
// ═══════════════════════════════════════════════════════════════════

object FilterParser {

    // Scriptlet rule: youtube.com##+js(name, args...)
    private val SCRIPTLET_RE = Regex("""^[^#]*youtube\.com[^#]*#\+js\(([^)]+)\)""")

    // Cosmetic rule: youtube.com##selector
    private val COSMETIC_RE  = Regex("""^[^#]*youtube\.com##(.+)$""")

    // Network rule: ||pattern^  (not youtube.com itself)
    private val NETWORK_RE   = Regex("""^\|\|([^|^]+)\^""")

    // Domains to not block (whitelist — avoid breaking YouTube core)
    private val NETWORK_WHITELIST = setOf(
        "youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com",
        "googleapis.com", "gstatic.com"
    )

    fun parse(txt: String): ParsedRules {
        val scriptlets = mutableListOf<ScriptletRule>()
        val cosmetic   = mutableListOf<String>()
        val network    = mutableListOf<String>()

        val seen = mutableSetOf<String>() // dedup

        txt.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!")) return@forEach

            // Scriptlet
            SCRIPTLET_RE.find(line)?.let { m ->
                val inner = m.groupValues[1].trim()
                // inner = "name, arg1 arg2" or just "name"
                val commaIdx = inner.indexOf(',')
                val name = if (commaIdx >= 0) inner.substring(0, commaIdx).trim() else inner.trim()
                val args = if (commaIdx >= 0) inner.substring(commaIdx + 1).trim() else ""
                val key  = "$name|$args"
                if (seen.add(key)) scriptlets.add(ScriptletRule(name, args))
                return@forEach
            }

            // Cosmetic
            COSMETIC_RE.find(line)?.let { m ->
                val sel = m.groupValues[1].trim()
                if (seen.add("css|$sel")) cosmetic.add(sel)
                return@forEach
            }

            // Network — only non-YouTube domains
            NETWORK_RE.find(line)?.let { m ->
                val pattern = m.groupValues[1].trim()
                val domain  = pattern.substringBefore("/").substringBefore("?")
                if (NETWORK_WHITELIST.none { domain.endsWith(it) }) {
                    if (seen.add("net|$pattern")) network.add(pattern)
                }
                return@forEach
            }
        }

        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())

        return ParsedRules(scriptlets, cosmetic, network, ts)
    }
}

// END OF PART 6/10


// ═══════════════════════════════════════════════════════════════════
// === PART 7/10 — Rule Fetcher + Local Storage ===
// ═══════════════════════════════════════════════════════════════════
//
// RulesRepository:
//   load()   — reads ParsedRules from app files dir (or empty if none)
//   update() — fetches filter txt, parses, saves, returns result
//
// Storage: app.filesDir / RULES_FILENAME (JSON)
// All IO on Dispatchers.IO
//
// ═══════════════════════════════════════════════════════════════════

class RulesRepository(private val context: Context) {

    private val file get() = File(context.filesDir, RULES_FILENAME)

    fun load(): ParsedRules {
        if (!file.exists()) return ParsedRules()
        return try { parseJsonRules(file.readText()) } catch (e: Exception) { ParsedRules() }
    }

    suspend fun update(): Result<ParsedRules> = withContext(Dispatchers.IO) {
        try {
            val txt = URL(UBLOCK_FILTERS_URL).readText()
            val rules = FilterParser.parse(txt)
            file.writeText(rules.toJson())
            Result.success(rules)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// END OF PART 7/10


// ═══════════════════════════════════════════════════════════════════
// === PART 8/10 — Ad Blocker Engine ===
// ═══════════════════════════════════════════════════════════════════
//
// AdBlocker:
//   buildPageJs(rules)     — full JS to inject at onPageStarted
//                            includes: dark mode patch + scriptlets + cosmetic
//   shouldBlock(url)       — returns true if URL matches network rules
//
// Cosmetic injection: base64-encoded <style> added via JS to survive
// YouTube's dynamic rendering and SPA navigation.
//
// ═══════════════════════════════════════════════════════════════════

object AdBlocker {

    // Dark mode + cookie patch (was in V1.4 onPageStarted)
    private val DARK_MODE_JS = """
(function(){
    document.documentElement.style.setProperty('color-scheme','dark');
    document.documentElement.style.setProperty('background-color','#0A0A0A');
    var origMM = window.matchMedia;
    window.matchMedia = function(q) {
        var r = origMM(q);
        if (q.includes('prefers-color-scheme')) {
            return { matches:true, media:q, onchange:null,
                addListener:function(cb){cb(this);},
                removeListener:function(){},
                addEventListener:function(t,cb){if(t==='change')cb(this);},
                removeEventListener:function(){},
                dispatchEvent:function(){return true;} };
        }
        return r;
    };
    document.cookie='PREF=f6=4;path=/;domain=.youtube.com';
})();
""".trimIndent()

    // Cosmetic CSS injection via base64 <style> — survives SPA navigation
    private fun buildCosmeticJs(selectors: List<String>): String {
        if (selectors.isEmpty()) return ""
        val css = selectors.joinToString(",\n") + " { display:none!important; visibility:hidden!important; }"
        val b64 = android.util.Base64.encodeToString(css.toByteArray(), android.util.Base64.NO_WRAP)
        return """
(function(){
    function injectCss() {
        if (document.getElementById('__ht_cosmetic__')) return;
        var s = document.createElement('style');
        s.id = '__ht_cosmetic__';
        s.textContent = atob('$b64');
        (document.head || document.documentElement).appendChild(s);
    }
    injectCss();
    new MutationObserver(injectCss).observe(document.documentElement,
        { childList:true, subtree:true });
})();
""".trimIndent()
    }

    fun buildPageJs(rules: ParsedRules): String {
        val sb = StringBuilder()
        sb.append(DARK_MODE_JS)
        sb.append("\n")
        sb.append(ScriptletLibrary.buildInjectionJs(rules.scriptlets))
        sb.append("\n")
        sb.append(buildCosmeticJs(rules.cosmetic))
        return sb.toString()
    }

    fun shouldBlock(url: String, rules: ParsedRules): Boolean {
        return rules.network.any { pattern ->
            url.contains(pattern, ignoreCase = true)
        }
    }
}

// END OF PART 8/10


// ═══════════════════════════════════════════════════════════════════
// === PART 9/10 — Bottom Sheet UI ===
// ═══════════════════════════════════════════════════════════════════

@Composable
fun HueTubeBottomSheet(
    visible: Boolean,
    adBlockEnabled: Boolean,
    onToggleAdBlock: (Boolean) -> Unit,
    updateStatus: String,
    isUpdating: Boolean,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    // Scrim
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() }
    )

    // Sheet
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(SHEET_BG)
                .clickable(enabled = false) {} // consume clicks so scrim doesn't fire
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount > 40f) onDismiss()
                    }
                }
                .padding(bottom = 32.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF444444))
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Ad Blocking Row ──────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Ad Blocking", color = TEXT_PRI, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Scriptlets + cosmetic filters", color = TEXT_SEC, fontSize = 12.sp)
                }
                Switch(
                    checked = adBlockEnabled,
                    onCheckedChange = onToggleAdBlock,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ACCENT,
                        uncheckedThumbColor = Color(0xFF888888),
                        uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Divider(color = DIVIDER, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(modifier = Modifier.height(12.dp))

            // ── Update Rules Row ─────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Filter Rules", color = TEXT_PRI, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (updateStatus.isEmpty()) "Tap to fetch latest uBlock rules"
                        else updateStatus,
                        color = TEXT_SEC, fontSize = 12.sp
                    )
                }
                Button(
                    onClick = onUpdate,
                    enabled = !isUpdating,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF222222),
                        contentColor = TEXT_PRI,
                        disabledContainerColor = Color(0xFF1A1A1A),
                        disabledContentColor = TEXT_SEC
                    )
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = TEXT_SEC,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Update", fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = DIVIDER, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 20.dp))

            // ── Future features go below this line ───────────────
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// END OF PART 9/10


// ═══════════════════════════════════════════════════════════════════
// === PART 10/10 — Main App Composable ===
// ═══════════════════════════════════════════════════════════════════

@Composable
fun HueTubeApp() {
    val context    = LocalContext.current
    val activity   = context as? android.app.Activity ?: return
    val scope      = rememberCoroutineScope()
    val repo       = remember { RulesRepository(context) }

    val fullscreenManager = remember { FullscreenManager(activity) }

    // ── State ────────────────────────────────────────────────────
    var isFullscreen       by remember { mutableStateOf(false) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var showSheet          by remember { mutableStateOf(false) }
    var adBlockEnabled     by remember { mutableStateOf(true) }
    var isUpdating         by remember { mutableStateOf(false) }
    var updateStatus       by remember { mutableStateOf("") }
    var rules              by remember { mutableStateOf(ParsedRules()) }

    // Load rules from disk on start
    LaunchedEffect(Unit) {
        rules = withContext(Dispatchers.IO) { repo.load() }
        if (rules.updatedAt.isNotEmpty()) {
            updateStatus = "Updated ${rules.updatedAt}"
        }
    }

    // ── Permanent container ──────────────────────────────────────
    val container = remember {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
        }
    }

    // ── Single WebView ───────────────────────────────────────────
    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            with(settings) {
                javaScriptEnabled            = true
                domStorageEnabled            = true
                loadWithOverviewMode         = true
                useWideViewPort              = true
                builtInZoomControls          = true
                displayZoomControls          = false
                setSupportZoom(true)
                mediaPlaybackRequiresUserGesture = false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreenCallback = callback
                    isFullscreen = true
                    fullscreenManager.enterFullscreen(view, callback, container, this@apply)
                }
                override fun onHideCustomView() {
                    fullscreenManager.exitFullscreen(fullscreenCallback)
                    fullscreenCallback = null
                    isFullscreen = false
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (adBlockEnabled) {
                        view.evaluateJavascript(AdBlocker.buildPageJs(rules), null)
                    } else {
                        // Still inject dark mode even if ad block off
                        view.evaluateJavascript("""
                            (function(){
                                document.documentElement.style.setProperty('color-scheme','dark');
                                document.cookie='PREF=f6=4;path=/;domain=.youtube.com';
                            })();
                        """.trimIndent(), null)
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    if (adBlockEnabled) {
                        val url = request.url.toString()
                        if (AdBlocker.shouldBlock(url, rules)) {
                            return WebResourceResponse("text/plain", "utf-8", null)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            loadUrl(HOMEPAGE_URL)
        }
    }

    // Add WebView to container once
    LaunchedEffect(Unit) { container.addView(webView) }

    // ── Back Handler ─────────────────────────────────────────────
    BackHandler {
        if (showSheet) { showSheet = false; return@BackHandler }
        if (isFullscreen) {
            fullscreenManager.exitFullscreen(fullscreenCallback)
            fullscreenCallback = null
            isFullscreen = false
            return@BackHandler
        }
        val url = webView.url ?: ""
        when {
            webView.canGoBack()                                           -> webView.goBack()
            url.startsWith(HOMEPAGE_URL) || url == "about:blank"         -> activity.finish()
            else                                                          -> webView.loadUrl(HOMEPAGE_URL)
        }
    }

    // ── Layout ───────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .systemBarsPadding()
    ) {
        // WebView
        AndroidView(factory = { container }, modifier = Modifier.fillMaxSize())

        // Floating menu button — bottom-left, only when not fullscreen
        if (!isFullscreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 14.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC141414))
                    .clickable { showSheet = true },
                contentAlignment = Alignment.Center
            ) {
                // Three-line menu icon drawn with boxes
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .width(16.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(Color(0xCCEEEEEE))
                        )
                    }
                }
            }
        }

        // Bottom Sheet overlay
        if (showSheet) {
            HueTubeBottomSheet(
                visible        = showSheet,
                adBlockEnabled = adBlockEnabled,
                onToggleAdBlock = { adBlockEnabled = it },
                updateStatus   = updateStatus,
                isUpdating     = isUpdating,
                onUpdate       = {
                    scope.launch {
                        isUpdating   = true
                        updateStatus = "Updating..."
                        val result   = repo.update()
                        result.onSuccess { updated ->
                            rules        = updated
                            updateStatus = "Updated ${updated.updatedAt}"
                        }
                        result.onFailure {
                            updateStatus = "Failed — check connection"
                        }
                        isUpdating = false
                    }
                },
                onDismiss = { showSheet = false }
            )
        }
    }
}

// END OF PART 10/10

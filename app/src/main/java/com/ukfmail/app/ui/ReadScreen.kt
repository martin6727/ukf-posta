package com.ukfmail.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forward
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.ukfmail.app.Draft
import com.ukfmail.app.MailViewModel
import com.ukfmail.app.Screen
import com.ukfmail.app.mail.BodyParser
import com.ukfmail.app.mail.MailBody
import com.ukfmail.app.mail.MailHeader
import com.ukfmail.app.mail.Protocol
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(vm: MailViewModel, screen: Screen.Read, snackbar: SnackbarHostState, onOpenFile: (File, String) -> Unit) {
    val body = vm.body
    val header = body?.header ?: vm.messages.firstOrNull { it.uid == screen.header.uid } ?: screen.header
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Späť") } },
                actions = {
                    if (vm.protocol == Protocol.IMAP) {
                        IconButton(onClick = { vm.toggleFlag(header) }) {
                            if (header.flagged) Icon(Icons.Filled.Star, "Zrušiť hviezdičku", tint = Color(0xFFF5B301))
                            else Icon(Icons.Outlined.StarOutline, "Označiť hviezdičkou")
                        }
                    }
                    if (header.folder != com.ukfmail.app.LOCAL_SENT.fullName) {
                        IconButton(onClick = { vm.toggleSeen(header.copy(seen = true)); vm.back() }) {
                            Icon(Icons.Outlined.MarkEmailUnread, "Označiť ako neprečítané")
                        }
                    }
                    IconButton(onClick = { vm.delete(header) }) { Icon(Icons.Outlined.Delete, "Zmazať") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (body != null) {
                Surface(
                    shape = RoundedCornerShape(30.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 12.dp,
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        GradientButton(
                            onClick = { vm.reply(false) },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Reply, null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Odpovedať", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(6.dp))
                        RoundAction(Icons.AutoMirrored.Outlined.ReplyAll, "Odpovedať všetkým") { vm.reply(true) }
                        RoundAction(Icons.Outlined.Forward, "Preposlať") { vm.forward() }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            // The header we already know shows at once; the mail fades in over it when it has loaded.
            var ready by remember(screen) { mutableStateOf(false) }
            val webAlpha by animateFloatAsState(if (ready) 1f else 0f, tween(280), label = "web")
            if (body != null) {
                MessageWebView(
                    body = body,
                    showRemote = vm.showRemote,
                    onShowRemote = { vm.showRemote = true },
                    onAttachment = { i ->
                        body.attachments.getOrNull(i)?.let { a -> vm.saveAttachment(a) { f -> onOpenFile(f, a.mimeType) } }
                    },
                    onMailto = { uri -> vm.compose(Draft(to = uri.schemeSpecificPart.substringBefore('?'))) },
                    onReady = { ready = true },
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = webAlpha },
                )
            }
            when {
                vm.bodyError != null -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.padding(6.dp))
                    Text(vm.bodyError!!)
                    Spacer(Modifier.padding(8.dp))
                    OutlinedButton(onClick = { vm.retryOpen() }, shape = RoundedCornerShape(14.dp)) { Text("Skúsiť znova") }
                }
                webAlpha < 1f -> ReadPlaceholder(header, Modifier.graphicsLayer { alpha = (1f - webAlpha * 1.8f).coerceAtLeast(0f) })
            }
        }
    }
}

/** Same layout as the page's header card, plus pulsing lines where the text will be. */
@Composable
private fun ReadPlaceholder(h: MailHeader, modifier: Modifier) {
    val dark = isDark()
    val card = if (dark) Color(0xFF191A22) else Color(0xFFF4F4F9)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(0.35f, 0.85f, infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "a")
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 14.dp, end = 14.dp, top = 4.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(card)
                .drawBehind {
                    val w = size.width
                    val ia = if (dark) 0.38f else 0.20f
                    val pa = if (dark) 0.26f else 0.14f
                    drawRect(Brush.radialGradient(listOf(Indigo.copy(alpha = ia), Color.Transparent), Offset.Zero, w * 0.72f))
                    drawRect(Brush.radialGradient(listOf(Pink.copy(alpha = pa), Color.Transparent), Offset(w, 0f), w * 0.58f))
                }
        ) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
                if (h.subject.isNotBlank()) {
                    Text(h.subject, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.48).sp)
                } else {
                    SkeletonBar(0.75f, a, 26.dp)
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (h.fromName.isNotBlank()) Avatar(h.fromName, h.fromEmail.ifBlank { h.fromName }, 46.dp)
                    else Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = a)))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        if (h.fromName.isNotBlank()) {
                            Text(h.fromName, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                            if (h.fromEmail.isNotBlank() && h.fromEmail != h.fromName) Text(h.fromEmail, fontSize = 13.sp, color = muted)
                            Text(longDate(h.date), fontSize = 13.sp, color = muted)
                        } else {
                            SkeletonBar(0.5f, a); SkeletonBar(0.35f, a)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        for (f in listOf(1f, 0.93f, 0.97f, 0.55f)) SkeletonBar(f, a)
        Spacer(Modifier.height(14.dp))
        for (f in listOf(0.9f, 0.8f, 0.4f)) SkeletonBar(f, a)
    }
}

@Composable
private fun SkeletonBar(fraction: Float, alpha: Float, height: Dp = 13.dp) = Box(
    Modifier
        .padding(vertical = 6.dp)
        .fillMaxWidth(fraction)
        .height(height)
        .clip(RoundedCornerShape(7.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = alpha))
)

@Composable
private fun RoundAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) { Icon(icon, description) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MessageWebView(
    body: MailBody,
    showRemote: Boolean,
    onShowRemote: () -> Unit,
    onAttachment: (Int) -> Unit,
    onMailto: (Uri) -> Unit,
    onReady: () -> Unit,
    modifier: Modifier,
) {
    val dark = isDark()
    val context = LocalContext.current
    val page = remember(body, dark) { MessageHtml.page(body, dark) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(if (dark) 0xFF0E0F14.toInt() else 0xFFFFFFFF.toInt())
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // Lay out at phone width; wide mail scrolls inside its own box instead of shifting the page.
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.textZoom = 100
                isVerticalScrollBarEnabled = false
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    // The page does its own dark mode; the browser's darkening clashed with the app colors.
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) = onReady()

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // Only a real tap on a link in the mail does anything. A meta refresh, an iframe or a
                        // redirect in the mail's HTML would otherwise open pages or other apps by itself.
                        if (!request.isForMainFrame || !request.hasGesture() || request.isRedirect) return true
                        val u = request.url
                        when (u.scheme?.lowercase()) {
                            "ukf" -> when (u.host) {
                                "remote" -> onShowRemote()
                                "att" -> u.lastPathSegment?.toIntOrNull()?.let(onAttachment)
                            }
                            "mailto" -> onMailto(u)
                            "http", "https", "tel" -> runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, u)
                                        .addCategory(Intent.CATEGORY_BROWSABLE)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            // Anything else (custom app links, intent:, file:, content:) is ignored.
                        }
                        return true
                    }
                }
            }
        },
        update = { w ->
            w.settings.blockNetworkLoads = !showRemote
            w.settings.blockNetworkImage = !showRemote
            val html = if (showRemote) page.replace(MessageHtml.REMOTE_BAR, "") else page
            if (w.tag != html) {
                w.tag = html
                w.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
            }
        },
    )
}

/** Builds the page: header card, optional remote-content bar, the mail, then attachments. */
object MessageHtml {
    const val REMOTE_BAR = "<a class=\"ukfx-remote\" href=\"ukf://remote\">Obrázky z internetu sú skryté kvôli súkromiu. <b>Zobraziť</b></a>"

    /** Mail that sets its own backgrounds can't be recolored safely, so in dark mode it sits on a white card. */
    private val OWN_BACKGROUNDS = Regex("""bgcolor\s*=|background(-color)?\s*:|background\s*=""", RegexOption.IGNORE_CASE)

    fun page(b: MailBody, dark: Boolean): String {
        val h = b.header
        val e = BodyParser::escape
        val isHtml = !b.html.startsWith("<div class=\"ukf-plain\">")
        val mail = if (isHtml) isolate(b.html) else b.html
        val mailClass = when {
            !isHtml || !dark -> "ukf-mail"
            OWN_BACKGROUNDS.containsMatchIn(b.html) -> "ukf-mail ukfx-paper"
            else -> "ukf-mail ukfx-simple"
        }
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta http-equiv=\"Content-Security-Policy\" content=\"script-src 'none'; frame-src 'none'; child-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<meta name=\"color-scheme\" content=\"${if (dark) "dark" else "light"}\">")
        sb.append("<style>").append(css(dark)).append("</style></head><body><div class=\"ukfx-root\">")
        sb.append("<div class=\"ukfx-head\">")
        if (h.flagged) sb.append("<span class=\"ukfx-chip\">★ S hviezdičkou</span>")
        sb.append("<div class=\"ukfx-title\">").append(e(h.subject)).append("</div>")
        sb.append("<div class=\"ukfx-from\"><span class=\"ukfx-av\" style=\"background:").append(avatarCss(h.fromEmail.ifBlank { h.fromName }))
            .append("\">").append(e(initials(h.fromName))).append("</span><div><div class=\"ukfx-name\">").append(e(h.fromName)).append("</div>")
        if (h.fromEmail.isNotBlank() && h.fromEmail != h.fromName) sb.append("<div class=\"ukfx-sub\">").append(e(h.fromEmail)).append("</div>")
        sb.append("<div class=\"ukfx-sub\">").append(e(longDate(h.date))).append("</div></div></div>")
        if (b.to.isNotEmpty()) sb.append("<div class=\"ukfx-to\">Komu: ").append(e(b.to.joinToString(", "))).append("</div>")
        if (b.cc.isNotEmpty()) sb.append("<div class=\"ukfx-to\">Kópia: ").append(e(b.cc.joinToString(", "))).append("</div>")
        sb.append("</div>")
        if (b.hasRemoteContent) sb.append(REMOTE_BAR)
        sb.append("<div class=\"").append(mailClass).append("\">").append(mail).append("</div>")
        if (b.attachments.isNotEmpty()) {
            sb.append("<div class=\"ukfx-atts\"><div class=\"ukfx-atth\">Prílohy (").append(b.attachments.size).append(")</div>")
            for (a in b.attachments) {
                sb.append("<a class=\"ukfx-att\" href=\"ukf://att/").append(a.index).append("\"><span class=\"ukfx-ic\">")
                    .append(e(extension(a.name))).append("</span><span><span class=\"ukfx-an\">").append(e(a.name))
                    .append("</span><span class=\"ukfx-as\">").append(e(fileSize(a.size.toLong()))).append("</span></span></a>")
            }
            sb.append("</div>")
        }
        sb.append("</div></body></html>")
        return sb.toString()
    }

    private val STYLE = Regex("(?is)<style[^>]*>(.*?)</style>")
    private val BODY = Regex("(?is)<body[^>]*>(.*)</body>")
    private val DOC_TAGS = Regex("(?is)<!doctype[^>]*>|</?(html|head|body)\\b[^>]*>|<meta[^>]*>|<title>.*?</title>|<link[^>]*>")
    /** Tags that can navigate, embed or submit on their own. Removed, the text between them stays. */
    private val UNSAFE_TAGS = Regex("(?is)</?(meta|link|base|iframe|frame|frameset|object|embed|applet|form|script|noscript|portal)\\b[^>]*>")

    /** Removes [pattern] until nothing changes, so nested tricks like `<me<meta>ta …>` don't survive. */
    private fun stripAll(s: String, pattern: Regex): String {
        var cur = s
        repeat(10) {
            val next = pattern.replace(cur, "")
            if (next == cur) return cur
            cur = next
        }
        return pattern.replace(cur, "").replace("<", "&lt;") // still changing after 10 rounds: give up on markup
    }
    /** `html`, `body` and `:root` at the start of a selector. */
    private val ROOT_SELECTOR = Regex("""(^|[},;>\s])(html|body|:root)(?=[\s,{.:#\[>~+])""", RegexOption.IGNORE_CASE)

    /**
     * Mail HTML is a whole document with its own CSS (`body{padding:0}`, `h1{...}`), which used to
     * restyle the app's page. Keep only the body and point the mail's html/body rules at its own box.
     */
    fun isolate(html: String): String {
        val css = STYLE.findAll(html).joinToString("\n") { ROOT_SELECTOR.replace(it.groupValues[1]) { m -> m.groupValues[1] + ".ukf-mail" } }
        val inner = stripAll((BODY.find(html)?.groupValues?.get(1) ?: html).replace(STYLE, "").replace(DOC_TAGS, ""), UNSAFE_TAGS)
        return (if (css.isNotBlank()) "<style>$css</style>" else "") + inner
    }

    private fun extension(name: String) = name.substringAfterLast('.', "").take(4).uppercase().ifBlank { "FILE" }

    private fun css(dark: Boolean): String {
        val text = if (dark) "#e8e9ef" else "#1b1d26"
        val muted = if (dark) "#a2a4b3" else "#636677"
        val line = if (dark) "#282a35" else "#e7e8ef"
        val card = if (dark) "#191a22" else "#f4f4f9"
        val bg = if (dark) "#0e0f14" else "#ffffff"
        val glowA = if (dark) ".38" else ".20"
        val glowB = if (dark) ".26" else ".14"
        val link = if (dark) "#a5a7fb" else "#5b5ef4"
        val font = "system-ui,-apple-system,Roboto,sans-serif"
        // The app's own parts use ukfx- classes and !important, so rules inside a mail can't change them.
        return """
            html,html body{margin:0!important;padding:0!important;background:$bg!important;color:$text;font-family:$font;-webkit-text-size-adjust:100%;overflow-x:hidden!important;min-width:0!important;width:auto!important}
            .ukfx-root{padding:4px 14px 28px!important;box-sizing:border-box!important;width:100%!important;overflow:hidden!important;overflow-wrap:anywhere}
            .ukfx-head{display:block!important;position:relative;padding:18px 18px 16px!important;margin:0 0 18px!important;border-radius:26px!important;background:radial-gradient(120% 90% at 0% 0%,rgba(91,94,244,$glowA),transparent 60%),radial-gradient(90% 80% at 100% 0%,rgba(236,72,153,$glowB),transparent 65%),$card!important;font-family:$font!important;color:$text!important;text-align:left!important}
            .ukfx-title{font-size:24px!important;line-height:30px!important;font-weight:800!important;letter-spacing:-.02em!important;margin:0 0 16px!important;color:$text!important}
            .ukfx-from{display:flex!important;gap:12px;align-items:center}
            .ukfx-av{flex:0 0 46px;width:46px!important;height:46px!important;box-shadow:0 6px 16px rgba(91,94,244,.3);border-radius:50%!important;color:#fff!important;display:flex!important;align-items:center;justify-content:center;font-weight:600!important;font-size:16px!important}
            .ukfx-name{font-size:15.5px!important;font-weight:700!important;color:$text!important}
            .ukfx-sub{color:$muted!important;font-size:13px!important;margin-top:1px}
            .ukfx-to{color:$muted!important;font-size:13px!important;margin:12px 0 0!important}
            .ukfx-chip{display:inline-block!important;font-size:12px!important;font-weight:600!important;color:#fff!important;background:linear-gradient(135deg,#5b5ef4,#ec4899)!important;border-radius:999px!important;padding:3px 10px!important;margin-bottom:10px}
            .ukfx-remote{display:block!important;background:$card!important;border-radius:14px!important;padding:12px 14px!important;margin:0 0 16px!important;color:$muted!important;font-size:13.5px!important;text-decoration:none!important;font-family:$font!important}
            .ukfx-remote b{color:$link!important}
            .ukf-mail{font-size:15.5px;line-height:1.55;overflow-x:auto}
            .ukfx-root>.ukf-mail{min-width:0!important;max-width:100%!important;width:auto!important;margin-left:0!important;margin-right:0!important;float:none!important;position:static!important}
            .ukf-mail *{max-width:100%!important;box-sizing:border-box}
            .ukf-mail td,.ukf-mail th{word-break:break-word}
            .ukf-mail img{height:auto!important}
            .ukf-mail pre{white-space:pre-wrap}
            .ukf-plain a,.ukf-mail a{color:$link}
            .ukf-plain blockquote{margin:8px 0;padding:2px 0 2px 12px;border-left:3px solid $line;color:$muted}
            .ukfx-paper{background:#fff!important;color:#1b1d26;border-radius:22px;padding:16px 14px;color-scheme:light}
            .ukfx-paper a{color:#4f46e5}
            .ukfx-simple *{color:inherit!important;background-color:transparent!important;border-color:$line!important}
            .ukfx-simple a{color:$link!important}
            .ukfx-atts{margin-top:24px!important;border-top:1px solid $line!important;padding-top:12px!important;font-family:$font!important}
            .ukfx-atth{font-size:13px!important;font-weight:600!important;color:$muted!important;text-transform:uppercase;letter-spacing:.04em;margin:4px 0 10px!important}
            .ukfx-att{display:flex!important;gap:12px;align-items:center;background:$card!important;border-radius:14px!important;padding:10px 12px!important;margin-bottom:8px!important;text-decoration:none!important;color:$text!important}
            .ukfx-ic{flex:0 0 42px;height:42px;border-radius:10px;background:#5b5ef4!important;color:#fff!important;display:flex!important;align-items:center;justify-content:center;font-size:11px!important;font-weight:700!important}
            .ukfx-an{display:block;font-size:14.5px!important;font-weight:600!important;word-break:break-all}
            .ukfx-as{display:block;color:$muted!important;font-size:12.5px!important}
        """.trimIndent()
    }
}

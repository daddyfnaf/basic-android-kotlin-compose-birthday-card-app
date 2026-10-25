package com.example.happybirthday

import android.Manifest
import android.app.ActivityOptions
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

val BgBlack = Color(0xFF000000)
val CardSurface = Color(0xFF111111)
val OutlineGray = Color(0xFF262626)
val DotInactive = Color(0xFF1E1E1E)
val TextGray = Color(0xFF888888)
val AccentRed = Color(0xFFD71920)

const val YANDEX_MUSIC_PKG = "ru.yandex.music"
const val TWOGIS_PKG = "ru.dublgis.dgismobile"
const val ACTION_GO_HOME = "com.example.happybirthday.ACTION_GO_HOME"

class MediaNotificationListener : NotificationListenerService()

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap
)

class StaticHomeButtonService : Service() {
    private var windowManager: WindowManager? = null
    private var staticCornerView: View? = null
    private var attachedDisplayId: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val targetDisplayId = intent?.getIntExtra(EXTRA_DISPLAY_ID, 0) ?: 0
        if (staticCornerView != null && attachedDisplayId == targetDisplayId) {
            return START_NOT_STICKY
        }

        removeStaticView()
        attachStaticButtonToDisplay(targetDisplayId)
        return START_NOT_STICKY
    }

    private fun attachStaticButtonToDisplay(displayId: Int) {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val targetDisplay = displayManager.getDisplay(displayId) ?: displayManager.getDisplay(0)

        val baseDisplayContext = if (targetDisplay != null) {
            createDisplayContext(targetDisplay)
        } else {
            this
        }

        val overlayContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && targetDisplay != null) {
            runCatching {
                baseDisplayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null
                )
            }.getOrDefault(baseDisplayContext)
        } else {
            baseDisplayContext
        }

        val density = overlayContext.resources.displayMetrics.density
        val widthPx = (76 * density).roundToInt()
        val heightPx = (34 * density).roundToInt()
        val marginPx = (16 * density).roundToInt()

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = marginPx
            y = marginPx
        }

        val view = NothingCornerHomeView(overlayContext, density) {
            goHomeOnDisplay(attachedDisplayId.takeIf { it >= 0 } ?: displayId)
        }

        val primaryWm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val addedToTarget = runCatching {
            primaryWm.addView(view, params)
            windowManager = primaryWm
            staticCornerView = view
            attachedDisplayId = displayId
            true
        }.getOrDefault(false)

        if (!addedToTarget) {
            runCatching {
                val fallbackWm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                fallbackWm.addView(view, params)
                windowManager = fallbackWm
                staticCornerView = view
                attachedDisplayId = 0
            }
        }
    }

    private fun goHomeOnDisplay(displayId: Int) {
        val homeIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_GO_HOME
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        val launchedOnTarget = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != 0) {
            runCatching {
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                startActivity(homeIntent, options.toBundle())
                true
            }.getOrDefault(false)
        } else {
            false
        }

        if (!launchedOnTarget) {
            runCatching { startActivity(homeIntent) }
        }
    }

    private fun removeStaticView() {
        staticCornerView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        staticCornerView = null
        windowManager = null
        attachedDisplayId = -1
    }

    override fun onDestroy() {
        removeStaticView()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_DISPLAY_ID = "extra_display_id"

        fun startIfPermitted(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val displayId = safeGetDisplayId(context)
            val intent = Intent(context, StaticHomeButtonService::class.java).apply {
                putExtra(EXTRA_DISPLAY_ID, displayId)
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, StaticHomeButtonService::class.java))
            }
        }
    }
}

private class NothingCornerHomeView(
    context: Context,
    private val density: Float,
    private val onTap: () -> Unit
) : View(context) {

    private var isPressedState = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF141414.toInt()
        style = Paint.Style.FILL
    }
    private val bgPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD71920.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val redDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD71920.toInt()
        style = Paint.Style.FILL
    }
    private val whiteDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 11f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val rect = RectF()

    override fun onDraw(canvas: NativeCanvas) {
        super.onDraw(canvas)
        val strokeHalf = borderPaint.strokeWidth / 2f
        rect.set(strokeHalf, strokeHalf, width - strokeHalf, height - strokeHalf)
        val radius = 8f * density

        canvas.drawRoundRect(rect, radius, radius, if (isPressedState) bgPressedPaint else bgPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val dotCenterX = 14f * density
        val dotCenterY = height / 2f
        canvas.drawCircle(
            dotCenterX,
            dotCenterY,
            3.5f * density,
            if (isPressedState) whiteDotPaint else redDotPaint
        )

        val text = "HOME"
        val textY = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(text, 24f * density, textY, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressedState = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()
                if (isPressedState != inside) {
                    isPressedState = inside
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasPressed = isPressedState
                isPressedState = false
                invalidate()
                if (wasPressed && event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                    onTap()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressedState = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

private val DIGIT_MATRICES: Map<Char, IntArray> = mapOf(
    '0' to intArrayOf(
        0,1,1,1,0, 1,0,0,0,1, 1,0,0,0,1, 1,0,0,0,1, 1,0,0,0,1, 1,0,0,0,1, 0,1,1,1,0
    ),
    '1' to intArrayOf(
        0,0,1,0,0, 0,1,1,0,0, 0,0,1,0,0, 0,0,1,0,0, 0,0,1,0,0, 0,0,1,0,0, 0,1,1,1,0
    ),
    '2' to intArrayOf(
        0,1,1,1,0, 1,0,0,0,1, 0,0,0,0,1, 0,0,1,1,0, 0,1,0,0,0, 1,0,0,0,0, 1,1,1,1,1
    ),
    '3' to intArrayOf(
        1,1,1,1,0, 0,0,0,0,1, 0,0,0,0,1, 0,1,1,1,0, 0,0,0,0,1, 0,0,0,0,1, 1,1,1,1,0
    ),
    '4' to intArrayOf(
        1,0,0,1,0, 1,0,0,1,0, 1,0,0,1,0, 1,1,1,1,1, 0,0,0,1,0, 0,0,0,1,0, 0,0,0,1,0
    ),
    '5' to intArrayOf(
        1,1,1,1,1, 1,0,0,0,0, 1,0,0,0,0, 1,1,1,1,0, 0,0,0,0,1, 0,0,0,0,1, 1,1,1,1,0
    ),
    '6' to intArrayOf(
        0,1,1,1,0, 1,0,0,0,0, 1,0,0,0,0, 1,1,1,1,0, 1,0,0,0,1, 1,0,0,0,1, 0,1,1,1,0
    ),
    '7' to intArrayOf(
        1,1,1,1,1, 0,0,0,0,1, 0,0,0,1,0, 0,0,1,0,0, 0,0,1,0,0, 0,0,1,0,0, 0,0,1,0,0
    ),
    '8' to intArrayOf(
        0,1,1,1,0, 1,0,0,0,1, 1,0,0,0,1, 0,1,1,1,0, 1,0,0,0,1, 1,0,0,0,1, 0,1,1,1,0
    ),
    '9' to intArrayOf(
        0,1,1,1,0, 1,0,0,0,1, 1,0,0,0,1, 0,1,1,1,1, 0,0,0,0,1, 0,0,0,0,1, 0,1,1,1,0
    )
)

class MainActivity : ComponentActivity() {
    private var hasLocationPermission by mutableStateOf(false)
    private var isAppDrawerOpenState by mutableStateOf(false)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasLocationPermission = checkLocationPermission()
        if (!hasLocationPermission) {
            requestLocationPermissions()
        }

        StaticHomeButtonService.startIfPermitted(this)

        setContent {
            CarLauncherRoot(
                hasLocationPermission = hasLocationPermission,
                isAppDrawerOpen = isAppDrawerOpenState,
                onAppDrawerChange = { isAppDrawerOpenState = it },
                onRequestLocation = { requestLocationPermissions() }
            )
        }
    }

        override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_GO_HOME) {
            isAppDrawerOpenState = false
        }
    }

    override fun onResume() {
        super.onResume()
        hasLocationPermission = checkLocationPermission()
        StaticHomeButtonService.startIfPermitted(this)
    }

    override fun onDestroy() {
        if (isFinishing) {
            StaticHomeButtonService.stop(this)
        }
        super.onDestroy()
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }
}

@Composable
fun CarLauncherRoot(
    hasLocationPermission: Boolean,
    isAppDrawerOpen: Boolean,
    onAppDrawerChange: (Boolean) -> Unit,
    onRequestLocation: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var installedApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    LaunchedEffect(Unit) {
        installedApps = loadInstalledApps(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                if (hasOverlayPermission) {
                    StaticHomeButtonService.startIfPermitted(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = isAppDrawerOpen) {
        onAppDrawerChange(false)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack)
    ) {
        if (isAppDrawerOpen) {
            AppDrawerModal(
                apps = installedApps,
                hasOverlayPermission = hasOverlayPermission,
                onClose = { onAppDrawerChange(false) },
                onAppClick = { app ->
                    onAppDrawerChange(false)
                    launchPackageOnCarDisplay(context, app.packageName)
                }
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.56f)
                        .fillMaxHeight()
                ) {
                    MediaPlayerWidget(
                        hasOverlayPermission = hasOverlayPermission,
                        onRequestOverlayPermission = { requestOverlayPermission(context) }
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(0.44f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DotMatrixClock(modifier = Modifier.weight(0.95f))

                    Row(
                        modifier = Modifier
                            .weight(1.05f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SpeedometerWidget(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            hasPermission = hasLocationPermission,
                            onRequestPermission = onRequestLocation
                        )
                        TwoGisButton(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }

                    AllAppsBarButton(
                        modifier = Modifier
                            .weight(0.6f)
                            .fillMaxWidth(),
                        onClick = { onAppDrawerChange(true) }
                    )
                }
            }
        }
    }
}

fun requestOverlayPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
// ===== СТЫК: НАЧАЛО ЧАСТИ 2 =====

@Composable
fun MediaPlayerWidget(
    hasOverlayPermission: Boolean,
    onRequestOverlayPermission: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isNotificationAccessGranted by remember {
        mutableStateOf(isNotificationServiceEnabled(context))
    }
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var metadata by remember { mutableStateOf<MediaMetadata?>(null) }
    var playbackState by remember { mutableStateOf<PlaybackState?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isNotificationAccessGranted = isNotificationServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(isNotificationAccessGranted) {
        if (!isNotificationAccessGranted) return@DisposableEffect onDispose {}

        val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val componentName = ComponentName(context, MediaNotificationListener::class.java)

        fun pickBestController(controllers: List<MediaController>?): MediaController? {
            if (controllers.isNullOrEmpty()) return null
            return controllers.firstOrNull {
                it.packageName == YANDEX_MUSIC_PKG && it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers.firstOrNull {
                it.packageName == YANDEX_MUSIC_PKG
            } ?: controllers.firstOrNull()
        }

        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            mediaController = pickBestController(controllers)
        }

        try {
            sessionManager?.addOnActiveSessionsChangedListener(listener, componentName)
            mediaController = pickBestController(sessionManager?.getActiveSessions(componentName))
        } catch (_: SecurityException) {
            isNotificationAccessGranted = false
        }

        onDispose {
            try {
                sessionManager?.removeOnActiveSessionsChangedListener(listener)
            } catch (_: Exception) {}
        }
    }

    DisposableEffect(mediaController) {
        val controller = mediaController
        if (controller == null) {
            metadata = null
            playbackState = null
            return@DisposableEffect onDispose {}
        }

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(m: MediaMetadata?) { metadata = m }
            override fun onPlaybackStateChanged(s: PlaybackState?) { playbackState = s }
            override fun onSessionDestroyed() { mediaController = null }
        }
        controller.registerCallback(callback)
        metadata = controller.metadata
        playbackState = controller.playbackState

        onDispose {
            runCatching { controller.unregisterCallback(callback) }
        }
    }

    CardContainer(modifier = Modifier.fillMaxSize()) {
        if (!isNotificationAccessGranted) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "ПЕРВИЧНАЯ НАСТРОЙКА NOTHING CAR",
                    color = TextGray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentRed)
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "1. ДАТЬ ДОСТУП К ЯНДЕКС.МУЗЫКЕ",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (!hasOverlayPermission) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(OutlineGray)
                            .clickable { onRequestOverlayPermission() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "2. ВКЛЮЧИТЬ УГЛОВУЮ КНОПКУ HOME",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .padding(start = if (hasOverlayPermission) 78.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.clickable {
                            openYandexMusicOrActivePlayer(context, mediaController)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (playbackState?.state == PlaybackState.STATE_PLAYING) AccentRed else OutlineGray,
                                    CircleShape
                                )
                        )
                        val sourceName = when (mediaController?.packageName) {
                            YANDEX_MUSIC_PKG -> "ЯНДЕКС МУЗЫКА"
                            null -> "Я.МУЗЫКА"
                            else -> "ПЛЕЕР"
                        }
                        Text(
                            text = sourceName,
                            color = TextGray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!hasOverlayPermission) {
                        Text(
                            text = "+ HOME BTN",
                            color = AccentRed,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { onRequestOverlayPermission() }
                        )
                    } else {
                        Text(
                            text = "OPEN ↗",
                            color = AccentRed,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                openYandexMusicOrActivePlayer(context, mediaController)
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArt(
                        metadata = metadata,
                        onClick = { openYandexMusicOrActivePlayer(context, mediaController) }
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "НЕТ АКТИВНОГО ТРЕКА",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                                ?: "Нажми PLAY для запуска Яндекс.Музыки",
                            color = TextGray,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                        TrackProgressSection(playbackState = playbackState, duration = duration)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ControlButton(
                        modifier = Modifier.weight(1f),
                        label = "|<<",
                        onClick = { mediaController?.transportControls?.skipToPrevious() }
                    )
                    val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
                    ControlButton(
                        modifier = Modifier.weight(1.3f),
                        label = if (isPlaying) "PAUSE" else "PLAY",
                        accent = true,
                        onClick = {
                            val ctrl = mediaController
                            if (ctrl == null) {
                                openYandexMusicOrActivePlayer(context, null)
                            } else if (isPlaying) {
                                ctrl.transportControls.pause()
                            } else {
                                ctrl.transportControls.play()
                            }
                        }
                    )
                    ControlButton(
                        modifier = Modifier.weight(1f),
                        label = ">>|",
                        onClick = { mediaController?.transportControls?.skipToNext() }
                    )
                }
            }
        }
    }
}

fun openYandexMusicOrActivePlayer(context: Context, controller: MediaController?) {
    val targetPkg = controller?.packageName ?: YANDEX_MUSIC_PKG
    if (!launchPackageOnCarDisplay(context, targetPkg)) {
        Toast.makeText(context, "Яндекс.Музыка не установлена", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun AlbumArt(metadata: MediaMetadata?, onClick: () -> Unit) {
    val imageBitmap: ImageBitmap? = remember(metadata) {
        runCatching {
            val rawBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

            rawBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.let { bmp ->
                val scaled = if (bmp.width > 160 || bmp.height > 160) {
                    Bitmap.createScaledBitmap(bmp, 160, 160, true)
                } else {
                    bmp
                }
                scaled.asImageBitmap()
            }
        }.getOrNull()
    }

    Box(
        modifier = Modifier
            .size(92.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgBlack)
            .border(1.dp, OutlineGray, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = "Album Art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "Я.М",
                color = AccentRed,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun TrackProgressSection(playbackState: PlaybackState?, duration: Long) {
    val updatedDuration by rememberUpdatedState(duration)
    var currentPosition by remember(playbackState, duration) {
        val initialPos = (playbackState?.position ?: 0L).coerceAtLeast(0L)
        mutableLongStateOf(if (duration > 0L) initialPos.coerceIn(0L, duration) else initialPos)
    }

    LaunchedEffect(playbackState, duration) {
        val state = playbackState ?: return@LaunchedEffect
        while (state.state == PlaybackState.STATE_PLAYING) {
            val basePos = state.position.coerceAtLeast(0L)
            val timeDelta = if (state.lastPositionUpdateTime > 0L) {
                (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
            } else {
                0L
            }
            val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
            val calculatedPos = basePos + (timeDelta * speed).toLong()
            val maxDur = updatedDuration
            currentPosition = if (maxDur > 0L) calculatedPos.coerceIn(0L, maxDur) else calculatedPos
            delay(1000L)
        }
    }

    val progress = if (duration > 0L) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(OutlineGray, RoundedCornerShape(3.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .background(AccentRed, RoundedCornerShape(3.dp))
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(currentPosition),
                color = TextGray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = formatTime(duration),
                color = TextGray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun ControlButton(
    modifier: Modifier = Modifier,
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(if (accent) AccentRed else BgBlack)
            .border(1.dp, if (accent) AccentRed else OutlineGray, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun DotMatrixClock(modifier: Modifier = Modifier) {
    var currentTime by remember { mutableStateOf(getCurrentTimeString()) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            currentTime = getCurrentTimeString()
            delay(1000L - (now % 1000L))
        }
    }

    CardContainer(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            val text = currentTime
            val totalCols = 27
            val totalRows = 7

            val stepX = size.width / totalCols
            val stepY = size.height / totalRows
            val step = min(stepX, stepY)
            val dotRadius = step * 0.36f

            val gridWidth = totalCols * step
            val gridHeight = totalRows * step
            val startX = (size.width - gridWidth) / 2f
            val startY = (size.height - gridHeight) / 2f

            var currentColOffset = 0

            text.forEach { char ->
                val charCols = if (char == ':') 3 else 5
                val matrix = DIGIT_MATRICES[char]

                for (row in 0 until totalRows) {
                    for (col in 0 until charCols) {
                        val isActive = if (char == ':') {
                            col == 1 && (row == 2 || row == 4)
                        } else {
                            matrix != null && matrix[row * 5 + col] == 1
                        }

                        val cx = startX + (currentColOffset + col) * step + step / 2f
                        val cy = startY + row * step + step / 2f

                        drawCircle(
                            color = if (isActive) Color.White else DotInactive,
                            radius = dotRadius,
                            center = Offset(cx, cy)
                        )
                    }
                }
                currentColOffset += charCols + 1
            }
        }
    }
}

@Composable
fun SpeedometerWidget(
    modifier: Modifier = Modifier,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current
    var speedKmh by remember { mutableIntStateOf(0) }
    var lastLocationFixRealtime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        while (true) {
            delay(2000L)
            if (lastLocationFixRealtime > 0L &&
                SystemClock.elapsedRealtime() - lastLocationFixRealtime > 4000L
            ) {
                speedKmh = 0
            }
        }
    }

    DisposableEffect(hasPermission) {
        if (!hasPermission) return@DisposableEffect onDispose {}

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocationFixRealtime = SystemClock.elapsedRealtime()
                val speedMs = if (location.hasSpeed()) location.speed else 0f
                speedKmh = (speedMs * 3.6f).roundToInt().coerceAtLeast(0)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                speedKmh = 0
            }
        }

        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                listener
            )
        } catch (_: Exception) {}

        onDispose {
            runCatching { locationManager?.removeUpdates(listener) }
        }
    }

    CardContainer(
        modifier = modifier.clickable(enabled = !hasPermission) { onRequestPermission() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!hasPermission) {
                Text(
                    text = "GPS OFF",
                    color = AccentRed,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "РАЗРЕШИТЬ",
                    color = TextGray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    text = "$speedKmh",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "KM/H",
                    color = AccentRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun TwoGisButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    CardContainer(
        modifier = modifier.clickable {
            val packages = listOf(
                TWOGIS_PKG,
                "ru.yandex.yandexnavi",
                "ru.yandex.yandexmaps",
                "com.google.android.apps.maps"
            )
            val opened = packages.any { launchPackageOnCarDisplay(context, it) }
            if (!opened) {
                Toast.makeText(context, "2ГИС не найден", Toast.LENGTH_SHORT).show()
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(AccentRed, CircleShape)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "2ГИС",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "НАВИГАЦИЯ",
                color = TextGray,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun AllAppsBarButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    CardContainer(
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = ":::",
                    color = AccentRed,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "ALL APPS",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "ОТКРЫТЬ СПИСОК →",
                color = TextGray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun AppDrawerModal(
    apps: List<AppEntry>,
    hasOverlayPermission: Boolean,
    onClose: () -> Unit,
    onAppClick: (AppEntry) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack)
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardSurface)
                .border(1.dp, OutlineGray, RoundedCornerShape(12.dp))
                .padding(start = if (hasOverlayPermission) 88.dp else 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "::: NOTHING APPS (${apps.size})",
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentRed)
                    .clickable { onClose() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "ЗАКРЫТЬ ✕",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(apps, key = { it.packageName }) { app ->
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardSurface)
                        .border(1.dp, OutlineGray, RoundedCornerShape(12.dp))
                        .clickable { onAppClick(app) }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = app.label,
                        color = Color.White,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

suspend fun loadInstalledApps(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val resolveInfos = runCatching { pm.queryIntentActivities(mainIntent, 0) }.getOrDefault(emptyList())
    resolveInfos
        .mapNotNull { info ->
            runCatching {
                val pkg = info.activityInfo?.packageName ?: return@runCatching null
                if (pkg == context.packageName) return@runCatching null

                val label = info.loadLabel(pm)?.toString() ?: pkg
                val drawable = info.loadIcon(pm) ?: return@runCatching null
                AppEntry(
                    label = label,
                    packageName = pkg,
                    icon = drawableToImageBitmap(drawable, 72)
                )
            }.getOrNull()
        }
        .distinctBy { it.packageName }
        .sortedWith(
            compareBy<AppEntry> {
                when (it.packageName) {
                    TWOGIS_PKG -> 0
                    YANDEX_MUSIC_PKG -> 1
                    else -> 2
                }
            }.thenBy { it.label.lowercase(Locale.getDefault()) }
        )
}

fun drawableToImageBitmap(drawable: Drawable, sizePx: Int): ImageBitmap {
    val safeDrawable = drawable.mutate()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = NativeCanvas(bitmap)
    safeDrawable.setBounds(0, 0, sizePx, sizePx)
    safeDrawable.draw(canvas)
    return bitmap.asImageBitmap()
}

fun safeGetDisplayId(context: Context): Int {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.displayId ?: 0
        } else {
            0
        }
    }.getOrDefault(0)
}

fun launchPackageOnCarDisplay(context: Context, packageName: String): Boolean {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val displayId = safeGetDisplayId(context)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != 0) {
        val launchedOnVirtual = runCatching {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            context.startActivity(launchIntent, options.toBundle())
            true
        }.getOrDefault(false)
        if (launchedOnVirtual) return true
    }

    return runCatching {
        context.startActivity(launchIntent)
        true
    }.getOrDefault(false)
}

@Composable
fun CardContainer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardSurface)
            .border(1.dp, OutlineGray, RoundedCornerShape(16.dp))
    ) {
        content()
    }
}

fun isNotificationServiceEnabled(context: Context): Boolean {
    return NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)
}

fun getCurrentTimeString(): String {
    val calendar = Calendar.getInstance()
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    return String.format(Locale.US, "%02d:%02d", hour, minute)
}

fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

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
        

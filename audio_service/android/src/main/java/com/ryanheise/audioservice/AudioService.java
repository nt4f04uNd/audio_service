package com.ryanheise.audioservice;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.LruCache;
import android.view.KeyEvent;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.MediaBrowserServiceCompat.BrowserRoot;
import androidx.media.VolumeProviderCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import android.net.Uri;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.view.FlutterMain;

public class AudioService extends MediaBrowserServiceCompat {
    public static final String CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED";
    public static final String CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    public static final String CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    public static final int CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1;
    public static final int CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 2;
    public static final int CONTENT_STYLE_CATEGORY_LIST_ITEM_HINT_VALUE = 3;
    public static final int CONTENT_STYLE_CATEGORY_GRID_ITEM_HINT_VALUE = 4;

    private static final String SHARED_PREFERENCES_NAME = "audio_service_preferences";

    private static final int NOTIFICATION_ID = 1124;
    private static final int REQUEST_CONTENT_INTENT = 1000;
    public static final String NOTIFICATION_CLICK_ACTION = "com.ryanheise.audioservice.NOTIFICATION_CLICK";
    private static final String BROWSABLE_ROOT_ID = "root";
    private static final String RECENT_ROOT_ID = "recent";
    // See the comment in onMediaButtonEvent to understand how the BYPASS keycodes work.
    // We hijack KEYCODE_MUTE and KEYCODE_MEDIA_RECORD since the media session subsystem
    // considers these keycodes relevant to media playback and will pass them on to us.
    public static final int KEYCODE_BYPASS_PLAY = KeyEvent.KEYCODE_MUTE;
    public static final int KEYCODE_BYPASS_PAUSE = KeyEvent.KEYCODE_MEDIA_RECORD;
    public static final int MAX_COMPACT_ACTIONS = 3;

    static AudioService instance;
    private static PendingIntent contentIntent;
    private static ServiceListener listener;
    private static List<MediaSessionCompat.QueueItem> queue = new ArrayList<MediaSessionCompat.QueueItem>();
    private static int queueIndex = -1;
    private static Map<String, MediaMetadataCompat> mediaMetadataCache = new HashMap<>();
    private static Set<String> artUriBlacklist = new HashSet<>();
    private static Long defaultArtBlendColor;

    public static void init(ServiceListener listener) {
        AudioService.listener = listener;
    }

    MediaMetadataCompat createMediaMetadata(String mediaId,
                                            String mediaUri,
                                            boolean loadArt,
                                            Long defaultArtBlendColor,
                                            String album,
                                            String title,
                                            String artist,
                                            String genre,
                                            Long duration,
                                            String artUri,
                                            Boolean playable,
                                            String displayTitle,
                                            String displaySubtitle,
                                            String displayDescription,
                                            RatingCompat rating,
                                            Map<?, ?> extras) {
        this.defaultArtBlendColor = defaultArtBlendColor;
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title);
        if (mediaUri != null)
            builder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, mediaUri);
        if (artist != null)
            builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
        if (genre != null)
            builder.putString(MediaMetadataCompat.METADATA_KEY_GENRE, genre);
        if (duration != null)
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        if (artUri != null) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artUri);
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artUri);
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUri);
        }
        if (loadArt) {
            Bitmap bitmap = null;
            if (artUri != null) {
                String artCacheFilePath = null;
                if (extras != null) {
                    artCacheFilePath = (String) extras.get("artCacheFile");
                }
                if (artCacheFilePath != null) {
                    bitmap = loadArtBitmapFromFile(artCacheFilePath);
                }
            } else if (mediaUri != null) {
                try {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(getApplicationContext(), Uri.parse(mediaUri));
                    byte[] bytes = retriever.getEmbeddedPicture();
                    if (bytes != null) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.outWidth = 192;
                        options.outHeight = 192;
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    }
                    retriever.release();
                } catch (IllegalArgumentException ex) {
                    ex.printStackTrace();
                    // Catch when the content by specified path doesn't exist
                }
            }
            if (bitmap == null) {
                bitmap = loadDefaultAlbumArt(false, defaultArtBlendColor);
                loadDefaultAlbumArt(true, defaultArtBlendColor);
            }
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap);
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap);
        }
        if (playable != null)
            builder.putLong("playable_long", playable ? 1 : 0);
        if (displayTitle != null)
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle);
        if (displaySubtitle != null)
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displaySubtitle);
        if (displayDescription != null)
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, displayDescription);
        if (rating != null) {
            builder.putRating(MediaMetadataCompat.METADATA_KEY_RATING, rating);
        }
        if (extras != null) {
            for (Object o : extras.keySet()) {
                String key = (String)o;
                Object value = extras.get(key);
                if (value instanceof Long) {
                    builder.putLong(key, (Long)value);
                } else if (value instanceof Integer) {
                    builder.putLong(key, (long)((Integer)value));
                } else if (value instanceof String) {
                    builder.putString(key, (String)value);
                } else if (value instanceof Boolean) {
                    builder.putLong(key, (Boolean)value ? 1 : 0);
                } else if (value instanceof Double) {
                    builder.putString(key, value.toString());
                }
            }
        }
        MediaMetadataCompat mediaMetadata = builder.build();
        mediaMetadataCache.put(mediaId, mediaMetadata);
        return mediaMetadata;
    }

    static MediaMetadataCompat getMediaMetadata(String mediaId) {
        return mediaMetadataCache.get(mediaId);
    }

    Bitmap loadDefaultAlbumArt(boolean forNotification, Long defaultArtBlendColor) {
        String cacheKey = forNotification ? "default_for_notification" : "default";
        Bitmap defaultBitmap = artBitmapCache.get(cacheKey);
            if (defaultBitmap == null && defaultArtBlendColor == null) {
                return defaultBitmap;
            } else {
                try {
                    AssetManager assetManager = getAssets();
                    String key = FlutterInjector.instance().flutterLoader().getLookupKeyForAsset(
                            forNotification ? "assets/images/logo_mask_thumb_notification.png"
                                            : "assets/images/logo_mask.png");
                    InputStream inputStream = assetManager.open(key);
                    Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                    if (defaultArtBlendColor == null)
                        defaultArtBlendColor = 4286336511L;
                    // Canvas can accept only mutable bitmaps
                    bitmap = bitmap.copy(bitmap.getConfig(), true);

                    // Applying color
                    // https://stackoverflow.com/a/31970565/9710294
                    Paint paint = new Paint();
                    ColorFilter filter = new PorterDuffColorFilter(defaultArtBlendColor.intValue(), PorterDuff.Mode.ADD);
                    paint.setColorFilter(filter);
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawBitmap(bitmap, 0, 0, paint);
                    artBitmapCache.put(cacheKey, bitmap);
                    return bitmap;
                } catch (Exception ex) {
                    System.out.println("Failed to load default album art");
                    return null;
            }
        }
    }

    Bitmap loadArtBitmapFromFile(String path) {
        Bitmap bitmap = artBitmapCache.get(path);
        if (bitmap != null) return bitmap;
        try {
            if (config.artDownscaleWidth != -1) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, options);
                int imageHeight = options.outHeight;
                int imageWidth = options.outWidth;
                options.inSampleSize = calculateInSampleSize(options, config.artDownscaleWidth, config.artDownscaleHeight);
                options.inJustDecodeBounds = false;

                bitmap = BitmapFactory.decodeFile(path, options);
            } else {
                bitmap = BitmapFactory.decodeFile(path);
            }
            artBitmapCache.put(path, bitmap);
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    private FlutterEngine flutterEngine;
    private AudioServiceConfig config;
    private PowerManager.WakeLock wakeLock;
    private MediaSessionCompat mediaSession;
    private MediaSessionCallback mediaSessionCallback;
    private NotificationReceiver notificationReceiver;
    private MediaMetadataCompat preparedMedia;
    private List<NotificationControl> controls;
    private int[] compactActionIndices;
    private MediaMetadataCompat mediaMetadata;
    private Object audioFocusRequest;
    private String notificationChannelId;
    private Handler handler = new Handler(Looper.getMainLooper());
    private LruCache<String, Bitmap> artBitmapCache;
    private boolean playing = false;
    private AudioProcessingState processingState = AudioProcessingState.idle;
    private int repeatMode;
    private int shuffleMode;
    private boolean notificationCreated;
    private String notificationAction;

    public AudioProcessingState getProcessingState() {
        return processingState;
    }

    public boolean isPlaying() {
        return playing;
    }

    public int getRepeatMode() {
        return repeatMode;
    }

    public int getShuffleMode() {
        return shuffleMode;
    }

    @Override
    public void onCreate() {
        System.out.println("### onCreate");
        super.onCreate();
        instance = this;
        String packageName = getApplication().getPackageName();
        notificationChannelId = packageName + ".channel";
        config = new AudioServiceConfig(getApplicationContext());

        if (config.activityClassName != null) {
            Context context = getApplicationContext();
            Intent intent = new Intent((String)null);
            intent.setComponent(new ComponentName(context, config.activityClassName));
            //Intent intent = new Intent(context, config.activityClassName);
            intent.setAction(NOTIFICATION_CLICK_ACTION);
            contentIntent = PendingIntent.getActivity(context, REQUEST_CONTENT_INTENT, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            contentIntent = null;
        }

        repeatMode = 0;
        shuffleMode = 0;
        notificationCreated = false;
        playing = false;
        processingState = AudioProcessingState.idle;

        mediaSession = new MediaSessionCompat(this, "media-session");
        if (!config.androidResumeOnClick) {
            System.out.println("### AudioService will not resume on click");
            mediaSession.setMediaButtonReceiver(null);
        } else {
            System.out.println("### AudioService will resume on click");
        }
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY);
        mediaSession.setPlaybackState(stateBuilder.build());
        mediaSession.setCallback(mediaSessionCallback = new MediaSessionCallback());
        setSessionToken(mediaSession.getSessionToken());
        mediaSession.setQueue(queue);

        notificationReceiver = new NotificationReceiver();
        notificationAction = packageName + ".notification_action";
        IntentFilter filter = new IntentFilter();
        filter.addAction(notificationAction);
        registerReceiver(notificationReceiver, filter);

        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AudioService.class.getName());

        // Get max available VM memory, exceeding this amount will throw an
        // OutOfMemory exception. Stored in kilobytes as LruCache takes an
        // int in its constructor.
        final int maxMemory = (int)(Runtime.getRuntime().maxMemory() / 1024);

        // Use 1/8th of the available memory for this memory cache.
        final int cacheSize = maxMemory / 8;

        artBitmapCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // The cache size will be measured in kilobytes rather than
                // number of items.
                return bitmap.getByteCount() / 1024;
            }
        };

        flutterEngine = AudioServicePlugin.getFlutterEngine(this);
        System.out.println("flutterEngine warmed up");
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        System.out.println("### onStartCommand");
        MediaButtonReceiver.handleIntent(mediaSession, intent);
        return START_NOT_STICKY;
    }

    public void stop() {
        deactivateMediaSession();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        System.out.println("### onDestroy");
        super.onDestroy();
        listener.onDestroy();
        listener = null;
        mediaMetadata = null;
        queue.clear();
        queueIndex = -1;
        mediaMetadataCache.clear();
        artBitmapCache.evictAll();
        compactActionIndices = null;
        releaseMediaSession();
        unregisterReceiver(notificationReceiver);
        controls = null;
        stopForeground(!config.androidResumeOnClick);
        // This still does not solve the Android 11 problem.
        // if (notificationCreated) {
        //     NotificationManager notificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        //     notificationManager.cancel(NOTIFICATION_ID);
        // }
        releaseWakeLock();
        instance = null;
        notificationCreated = false;
        notificationAction = null;
    }

    public void configure(AudioServiceConfig config) {
        this.config = config;
    }

    int getResourceId(String resource) {
        String[] parts = resource.split("/");
        String resourceType = parts[0];
        String resourceName = parts[1];
        return getResources().getIdentifier(resourceName, resourceType, getApplicationContext().getPackageName());
    }

    /** Action extras:
     *   -1 - delete notification
     *   0-4 - notification button clicks
     *   5 - for `setCancelButtonIntent` button, which is used only before Android Lollipop
     */
    private class NotificationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            if (intent.getAction().equals(notificationAction)) {
                int extra = intent.getIntExtra("index", -2);
                if (extra == -2) return;
                if (extra == -1) {
                    if (listener == null) return;
                    listener.onClose();
                } else if (extra == 5) {
                    mediaSessionCallback.onStop();
                } else {
                    listener.onNotificationAction(controls.get(extra).action);
                }
            }
        }
    }

    private PendingIntent buildPendingNotificationIntent(int actionIndex) {
        Intent intent = new Intent(notificationAction).putExtra("index", actionIndex);
        return PendingIntent.getBroadcast(
                AudioService.instance, actionIndex, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static class NotificationControl {
        public NotificationControl(String resource, String label, String action) {
            this.resource = resource;
            this.label = label;
            this.action = action;
        }
        String resource;
        String label;
        String action;
        private NotificationCompat.Action notificationAction;
    }

    void setState(List<NotificationControl> controls, int actionBits, int[] compactActionIndices, AudioProcessingState processingState, boolean playing, long position, long bufferedPosition, float speed, long updateTime, Integer errorCode, String errorMessage, int repeatMode, int shuffleMode, boolean captioningEnabled, Long queueIndex) {
        for (int i = 0; i < controls.size(); i++) {
            NotificationControl control = controls.get(i);
            int iconId = getResourceId(control.resource);
            control.notificationAction = new NotificationCompat.Action(
                    iconId,
                    control.label,
                    buildPendingNotificationIntent(i));
        }
        this.controls = controls;
        this.compactActionIndices = compactActionIndices;
        boolean wasPlaying = this.playing;
        AudioProcessingState oldProcessingState = this.processingState;
        this.processingState = processingState;
        this.playing = playing;
        this.repeatMode = repeatMode;
        this.shuffleMode = shuffleMode;

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | actionBits)
                .setState(getPlaybackState(), position, speed, updateTime)
                .setBufferedPosition(bufferedPosition);
        if (queueIndex != null)
            stateBuilder.setActiveQueueItemId(queueIndex);
        if (errorCode != null && errorMessage != null)
            stateBuilder.setErrorMessage(errorCode, errorMessage);
        else if (errorMessage != null)
            stateBuilder.setErrorMessage(errorMessage);
        mediaSession.setPlaybackState(stateBuilder.build());
        mediaSession.setRepeatMode(repeatMode);
        mediaSession.setShuffleMode(shuffleMode);
        mediaSession.setCaptioningEnabled(captioningEnabled);

        if (!wasPlaying && playing) {
            enterPlayingState();
        } else if (wasPlaying && !playing) {
            exitPlayingState();
        }

        if (oldProcessingState != AudioProcessingState.idle && processingState == AudioProcessingState.idle) {
            // TODO: Handle completed state as well?
            stop();
        }

        updateNotification();
    }

    private VolumeProviderCompat volumeProvider;
    public void setPlaybackInfo(int playbackType, Integer volumeControlType, Integer maxVolume, Integer volume) {
        if (playbackType == MediaControllerCompat.PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            // We have to wait 'til media2 before we can use AudioAttributes.
            mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
            volumeProvider = null;
        } else if (playbackType == MediaControllerCompat.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
            if (volumeProvider == null || volumeControlType != volumeProvider.getVolumeControl() || maxVolume != volumeProvider.getMaxVolume()) {
                volumeProvider = new VolumeProviderCompat(volumeControlType, maxVolume, volume) {
                    @Override
                    public void onSetVolumeTo(int volumeIndex) {
                        if (listener == null) return;
                        listener.onSetVolumeTo(volumeIndex);
                    }
                    @Override
                    public void onAdjustVolume(int direction) {
                        if (listener == null) return;
                        listener.onAdjustVolume(direction);
                    }
                };
            } else {
                volumeProvider.setCurrentVolume(volume);
            }
            mediaSession.setPlaybackToRemote(volumeProvider);
        } else {
            // silently ignore
        }
    }

    public int getPlaybackState() {
        switch (processingState) {
        case idle: return PlaybackStateCompat.STATE_NONE;
        case loading: return PlaybackStateCompat.STATE_CONNECTING;
        case buffering: return PlaybackStateCompat.STATE_BUFFERING;
        case ready: return playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        case completed: return playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        case error: return PlaybackStateCompat.STATE_ERROR;
        default: return PlaybackStateCompat.STATE_NONE;
        }
    }

    private Notification buildNotification() {
        int[] compactActionIndices = this.compactActionIndices;
        if (compactActionIndices == null) {
            compactActionIndices = new int[Math.min(MAX_COMPACT_ACTIONS, controls.size())];
            for (int i = 0; i < compactActionIndices.length; i++) compactActionIndices[i] = i;
        }
        NotificationCompat.Builder builder = getNotificationBuilder();
        if (mediaMetadata != null) {
            MediaDescriptionCompat description = mediaMetadata.getDescription();
            if (description.getTitle() != null)
                builder.setContentTitle(description.getTitle());
            if (description.getSubtitle() != null)
                builder.setContentText(description.getSubtitle());
            if (description.getDescription() != null)
                builder.setSubText(description.getDescription());
            Bitmap bitmap = description.getIconBitmap();
            if (bitmap != null) {
                if (bitmap == artBitmapCache.get("default")) {
                    bitmap = loadDefaultAlbumArt(true, defaultArtBlendColor);
                }
                builder.setLargeIcon(bitmap);
            }
        }
        if (config.androidNotificationClickStartsActivity)
            builder.setContentIntent(mediaSession.getController().getSessionActivity());
        if (config.notificationColor != -1)
            builder.setColor(config.notificationColor);
        for (NotificationControl control : controls) {
            builder.addAction(control.notificationAction);
        }
        final MediaStyle style = new MediaStyle()
            .setMediaSession(mediaSession.getSessionToken())
            .setShowActionsInCompactView(compactActionIndices);
        if (config.androidNotificationOngoing) {
            style.setShowCancelButton(true);
            style.setCancelButtonIntent(buildPendingNotificationIntent(5));
            builder.setOngoing(true);
        }
        builder.setStyle(style);
        Notification notification = builder.build();
        return notification;
    }

    private NotificationCompat.Builder getNotificationBuilder() {
        NotificationCompat.Builder notificationBuilder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            createChannel();
        int iconId = getResourceId(config.androidNotificationIcon);
        notificationBuilder = new NotificationCompat.Builder(this, notificationChannelId)
                .setSmallIcon(iconId)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setDeleteIntent(buildPendingNotificationIntent(-1));
        return notificationBuilder;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void createChannel() {
        NotificationManager notificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = notificationManager.getNotificationChannel(notificationChannelId);
        if (channel == null) {
            channel = new NotificationChannel(notificationChannelId, config.androidNotificationChannelName, NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(config.androidShowNotificationBadge);
            if (config.androidNotificationChannelDescription != null)
                channel.setDescription(config.androidNotificationChannelDescription);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void updateNotification() {
        if (!notificationCreated) return;
        NotificationManager notificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, buildNotification());
    }

    private boolean enterPlayingState() {
        startService(new Intent(AudioService.this, AudioService.class));
        if (!mediaSession.isActive())
            mediaSession.setActive(true);

        acquireWakeLock();
        mediaSession.setSessionActivity(contentIntent);
        internalStartForeground();
        return true;
    }

    private void exitPlayingState() {
        if (config.androidStopForegroundOnPause) {
            exitForegroundState();
        }
    }

    private void exitForegroundState() {
        stopForeground(false);
        releaseWakeLock();
    }

    private void internalStartForeground() {
        startForeground(NOTIFICATION_ID, buildNotification());
        notificationCreated = true;
    }

    private void acquireWakeLock() {
        if (!wakeLock.isHeld())
            wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock.isHeld())
            wakeLock.release();
    }

    private void activateMediaSession() {
        if (!mediaSession.isActive())
            mediaSession.setActive(true);
    }

    private void deactivateMediaSession() {
        System.out.println("### deactivateMediaSession");
        if (mediaSession.isActive()) {
            System.out.println("### deactivate mediaSession");
            mediaSession.setActive(false);
        }
        // Force cancellation of the notification
        NotificationManager notificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
    }

    private void releaseMediaSession() {
        System.out.println("### releaseMediaSession");
        if (mediaSession == null) return;
        deactivateMediaSession();
        System.out.println("### release mediaSession");
        mediaSession.release();
        mediaSession = null;
    }

    void enableQueue() {
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS | MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS);
    }

    void setQueue(List<MediaSessionCompat.QueueItem> queue) {
        this.queue = queue;
        mediaSession.setQueue(queue);
    }

    void playMediaItem(MediaDescriptionCompat description) {
        mediaSessionCallback.onPlayMediaItem(description);
    }

    void setMetadata(final MediaMetadataCompat mediaMetadata) {
        this.mediaMetadata = mediaMetadata;
        mediaSession.setMetadata(mediaMetadata);
        updateNotification();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        Boolean isRecentRequest = rootHints == null ? null : (Boolean)rootHints.getBoolean(BrowserRoot.EXTRA_RECENT);
        if (isRecentRequest == null) isRecentRequest = false;
        System.out.println("### onGetRoot. isRecentRequest=" + isRecentRequest);
        Bundle extras = config.getBrowsableRootExtras();
        return new BrowserRoot(isRecentRequest ? RECENT_ROOT_ID : BROWSABLE_ROOT_ID, extras);
        // The response must be given synchronously, and we can't get a
        // synchronous response from the Dart layer. For now, we hardcode
        // the root to "root". This may improve in media2.
        //return listener.onGetRoot(clientPackageName, clientUid, rootHints);
    }

    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaBrowserCompat.MediaItem>> result) {
        System.out.println("### onLoadChildren");
        onLoadChildren(parentMediaId, result, null);
    }

    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaBrowserCompat.MediaItem>> result, Bundle options) {
        if (listener == null) {
            result.sendResult(new ArrayList<MediaBrowserCompat.MediaItem>());
            return;
        }
        listener.onLoadChildren(parentMediaId, result, options);
    }

    @Override
    public void onLoadItem(String itemId, Result<MediaBrowserCompat.MediaItem> result) {
        if (listener == null) {
            result.sendResult(null);
            return;
        }
        listener.onLoadItem(itemId, result);
    }

    @Override
    public void onSearch(String query, Bundle extras, Result<List<MediaBrowserCompat.MediaItem>> result) {
        if (listener == null) {
            result.sendResult(new ArrayList<MediaBrowserCompat.MediaItem>());
            return;
        }
        listener.onSearch(query, extras, result);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (listener != null) {
            listener.onTaskRemoved();
        }
        super.onTaskRemoved(rootIntent);
    }

    public class MediaSessionCallback extends MediaSessionCompat.Callback {
        @Override
        public void onAddQueueItem(MediaDescriptionCompat description) {
            if (listener == null) return;
            listener.onAddQueueItem(getMediaMetadata(description.getMediaId()));
        }

        @Override
        public void onAddQueueItem(MediaDescriptionCompat description, int index) {
            if (listener == null) return;
            listener.onAddQueueItemAt(getMediaMetadata(description.getMediaId()), index);
        }

        @Override
        public void onRemoveQueueItem(MediaDescriptionCompat description) {
            if (listener == null) return;
            listener.onRemoveQueueItem(getMediaMetadata(description.getMediaId()));
        }

        @Override
        public void onRemoveQueueItemAt(int index) {
            if (listener == null) return;
            listener.onRemoveQueueItemAt(index);
        }

        @Override
        public void onPrepare() {
            System.out.println("### onPrepare. listener: " + listener);
            if (listener == null) return;
            if (!mediaSession.isActive())
                mediaSession.setActive(true);
            listener.onPrepare();
        }

        @Override
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
            if (listener == null) return;
            if (!mediaSession.isActive())
                mediaSession.setActive(true);
            listener.onPrepareFromMediaId(mediaId, extras);
        }

        @Override
        public void onPrepareFromSearch(String query, Bundle extras) {
            if (listener == null) return;
            if (!mediaSession.isActive())
                mediaSession.setActive(true);
            listener.onPrepareFromSearch(query, extras);
        }

        @Override
        public void onPrepareFromUri(Uri uri, Bundle extras) {
            if (listener == null) return;
            if (!mediaSession.isActive())
                mediaSession.setActive(true);
            listener.onPrepareFromUri(uri, extras);
        }

        @Override
        public void onPlay() {
            System.out.println("### onPlay. listener: " + listener);
            if (listener == null) return;
            listener.onPlay();
        }

        @Override
        public void onPlayFromMediaId(final String mediaId, final Bundle extras) {
            if (listener == null) return;
            listener.onPlayFromMediaId(mediaId, extras);
        }

        @Override
        public void onPlayFromSearch(final String query, final Bundle extras) {
            if (listener == null) return;
            listener.onPlayFromSearch(query, extras);
        }

        @Override
        public void onPlayFromUri(final Uri uri, final Bundle extras) {
            if (listener == null) return;
            listener.onPlayFromUri(uri, extras);
        }

        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
            System.out.println("### onMediaButtonEvent: " + (KeyEvent)mediaButtonEvent.getExtras().get(Intent.EXTRA_KEY_EVENT));
            System.out.println("### listener = " + listener);
            if (listener == null) return false;
            final KeyEvent event = (KeyEvent)mediaButtonEvent.getExtras().get(Intent.EXTRA_KEY_EVENT);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (event.getKeyCode()) {
                case KEYCODE_BYPASS_PLAY:
                    onPlay();
                    break;
                case KEYCODE_BYPASS_PAUSE:
                    onPause();
                    break;
                case KeyEvent.KEYCODE_MEDIA_STOP:
                    onStop();
                    break;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    onFastForward();
                    break;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    onRewind();
                    break;
                // Android unfortunately reroutes media button clicks to
                // KEYCODE_MEDIA_PLAY/PAUSE instead of the expected KEYCODE_HEADSETHOOK
                // or KEYCODE_MEDIA_PLAY_PAUSE. As a result, we can't genuinely tell if
                // onMediaButtonEvent was called because a media button was actually
                // pressed or because a PLAY/PAUSE action was pressed instead! To get
                // around this, we make PLAY and PAUSE actions use different keycodes:
                // KEYCODE_BYPASS_PLAY/PAUSE. Now if we get KEYCODE_MEDIA_PLAY/PUASE
                // we know it is actually a media button press.
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    // These are the "genuine" media button click events
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                    System.out.println("### calling onClick");
                    MediaControllerCompat controller = mediaSession.getController();
                    listener.onClick(mediaControl(event));
                    System.out.println("### called onClick");
                    break;
                }
            }
            return true;
        }

        private MediaControl mediaControl(KeyEvent event) {
            switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return MediaControl.media;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return MediaControl.next;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return MediaControl.previous;
            default:
                return MediaControl.media;
            }
        }

        @Override
        public void onPause() {
            System.out.println("### onPause. listener: " + listener);
            if (listener == null) return;
            listener.onPause();
        }

        @Override
        public void onStop() {
            System.out.println("### onStop. listener: " + listener);
            if (listener == null) return;
            listener.onStop();
        }

        @Override
        public void onSkipToNext() {
            System.out.println("### onSkipToNext");
            if (listener == null) return;
            listener.onSkipToNext();
        }

        @Override
        public void onSkipToPrevious() {
            System.out.println("### onSkipToPrevious");
            if (listener == null) return;
            listener.onSkipToPrevious();
        }

        @Override
        public void onFastForward() {
            if (listener == null) return;
            listener.onFastForward();
        }

        @Override
        public void onRewind() {
            if (listener == null) return;
            listener.onRewind();
        }

        @Override
        public void onSkipToQueueItem(long id) {
            if (listener == null) return;
            listener.onSkipToQueueItem(id);
        }

        @Override
        public void onSeekTo(long pos) {
            if (listener == null) return;
            listener.onSeekTo(pos);
        }

        @Override
        public void onSetRating(RatingCompat rating) {
            if (listener == null) return;
            listener.onSetRating(rating);
        }

        @Override
        public void onSetCaptioningEnabled(boolean enabled) {
            if (listener == null) return;
            listener.onSetCaptioningEnabled(enabled);
        }

        @Override
        public void onSetRepeatMode(int repeatMode) {
            if (listener == null) return;
            listener.onSetRepeatMode(repeatMode);
        }

        @Override
        public void onSetShuffleMode(int shuffleMode) {
            if (listener == null) return;
            listener.onSetShuffleMode(shuffleMode);
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            if (listener == null) return;
            listener.onCustomAction(action, extras);
        }

        @Override
        public void onSetRating(RatingCompat rating, Bundle extras) {
            if (listener == null) return;
            listener.onSetRating(rating, extras);
        }

        //
        // NON-STANDARD METHODS
        //

        public void onPlayMediaItem(final MediaDescriptionCompat description) {
            if (listener == null) return;
            listener.onPlayMediaItem(getMediaMetadata(description.getMediaId()));
        }
    }

    public static interface ServiceListener {
        //BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints);
        void onLoadChildren(String parentMediaId, Result<List<MediaBrowserCompat.MediaItem>> result, Bundle options);
        void onLoadItem(String itemId, Result<MediaBrowserCompat.MediaItem> result);
        void onSearch(String query, Bundle extras, Result<List<MediaBrowserCompat.MediaItem>> result);
        void onClick(MediaControl mediaControl);
        void onPrepare();
        void onPrepareFromMediaId(String mediaId, Bundle extras);
        void onPrepareFromSearch(String query, Bundle extras);
        void onPrepareFromUri(Uri uri, Bundle extras);
        void onPlay();
        void onPlayFromMediaId(String mediaId, Bundle extras);
        void onPlayFromSearch(String query, Bundle extras);
        void onPlayFromUri(Uri uri, Bundle extras);
        void onSkipToQueueItem(long id);
        void onPause();
        void onSkipToNext();
        void onSkipToPrevious();
        void onFastForward();
        void onRewind();
        void onStop();
        void onSeekTo(long pos);
        void onSetRating(RatingCompat rating);
        void onSetRating(RatingCompat rating, Bundle extras);
        void onSetRepeatMode(int repeatMode);
        //void onSetShuffleModeEnabled(boolean enabled);
        void onSetShuffleMode(int shuffleMode);
        void onCustomAction(String action, Bundle extras);
        void onAddQueueItem(MediaMetadataCompat metadata);
        void onAddQueueItemAt(MediaMetadataCompat metadata, int index);
        void onRemoveQueueItem(MediaMetadataCompat metadata);
        void onRemoveQueueItemAt(int index);
        void onSetCaptioningEnabled(boolean enabled);
        void onSetVolumeTo(int volumeIndex);
        void onAdjustVolume(int direction);

        //
        // NON-STANDARD METHODS
        //

        void onPlayMediaItem(MediaMetadataCompat metadata);
        void onTaskRemoved();
        void onNotificationAction(String action);
        void onClose();
        void onDestroy();
    }
}

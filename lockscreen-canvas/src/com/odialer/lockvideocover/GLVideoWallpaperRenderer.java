package com.odialer.lockvideocover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Build;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 60FPS OpenGL ES 2.0 Renderer with Glitch-Free ColorOS 17 Transitions.
 * Supports:
 * - 60FPS Spotify/Apple Music Canvas video loops while playing.
 * - Full-screen static album art background when a song has no canvas.
 * - Idle Oppo Find X9 live wallpaper that plays exactly once and rests on final frame.
 */
public class GLVideoWallpaperRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "GLVideoRenderer";

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vTexCoord = (uSTMatrix * aTexCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform float uAlpha;\n" +
            "uniform vec2 uRippleCenter;\n" +
            "uniform float uRippleRadius;\n" +
            "uniform vec2 uResolution;\n" +
            "uniform int uUseRipple;\n" +
            "uniform int uTopGradientEnabled;\n" +
            "void main() {\n" +
            "  vec2 normCoord = gl_FragCoord.xy / uResolution.xy;\n" +
            "  vec2 diff = normCoord - uRippleCenter;\n" +
            "  diff.y *= (uResolution.y / uResolution.x);\n" +
            "  float dist = length(diff);\n" +
            "  vec2 coord = vTexCoord;\n" +
            "  float light = 0.0;\n" +
            "  if (uUseRipple == 1) {\n" +
            "    vec2 waveDir = (dist > 0.001) ? (diff / dist) : vec2(0.0, 1.0);\n" +
            "    float waveDist = dist - uRippleRadius;\n" +
            "    float waveZone = 1.0 - smoothstep(0.0, 0.22, abs(waveDist));\n" +
            "    float refraction = sin(waveDist * 32.0) * waveZone * 0.016;\n" +
            "    coord = clamp(vTexCoord + waveDir * refraction, 0.001, 0.999);\n" +
            "    float crest = exp(-waveDist * waveDist * 280.0);\n" +
            "    float softGlow = exp(-waveDist * waveDist * 35.0) * 0.14;\n" +
            "    light = crest * 0.40 + softGlow;\n" +
            "  }\n" +
            "  vec4 color = texture2D(sTexture, coord);\n" +
            "  color.rgb += vec3(light);\n" +
            "  // Top feathering reveal: blends video into stock wallpaper if enabled\n" +
            "  float topAlpha = 1.0;\n" +
            "  if (uTopGradientEnabled == 1) {\n" +
            "    float topBound = 0.77;\n" +
            "    topAlpha = smoothstep(topBound, topBound - 0.30, normCoord.y);\n" +
            "  }\n" +
            "  if (uUseRipple == 1) {\n" +
            "    float feather = 0.18;\n" +
            "    float radialAlpha = 1.0 - smoothstep(uRippleRadius - feather, uRippleRadius + feather, dist);\n" +
            "    gl_FragColor = vec4(color.rgb, color.a * uAlpha * radialAlpha * topAlpha);\n" +
            "  } else {\n" +
            "    gl_FragColor = vec4(color.rgb, color.a * uAlpha * topAlpha);\n" +
            "  }\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D sTexture;\n" +
            "uniform float uAlpha;\n" +
            "uniform vec2 uRippleCenter;\n" +
            "uniform float uRippleRadius;\n" +
            "uniform vec2 uResolution;\n" +
            "uniform int uUseRipple;\n" +
            "void main() {\n" +
            "  vec2 normCoord = gl_FragCoord.xy / uResolution.xy;\n" +
            "  vec2 diff = normCoord - uRippleCenter;\n" +
            "  diff.y *= (uResolution.y / uResolution.x);\n" +
            "  float dist = length(diff);\n" +
            "  vec2 coord = vTexCoord;\n" +
            "  float light = 0.0;\n" +
            "  if (uUseRipple == 1) {\n" +
            "    vec2 waveDir = (dist > 0.001) ? (diff / dist) : vec2(0.0, 1.0);\n" +
            "    float waveDist = dist - uRippleRadius;\n" +
            "    float waveZone = 1.0 - smoothstep(0.0, 0.22, abs(waveDist));\n" +
            "    float refraction = sin(waveDist * 32.0) * waveZone * 0.016;\n" +
            "    coord = clamp(vTexCoord + waveDir * refraction, 0.001, 0.999);\n" +
            "    float crest = exp(-waveDist * waveDist * 280.0);\n" +
            "    float softGlow = exp(-waveDist * waveDist * 35.0) * 0.14;\n" +
            "    light = crest * 0.40 + softGlow;\n" +
            "  }\n" +
            "  vec4 color = texture2D(sTexture, coord);\n" +
            "  color.rgb += vec3(light);\n" +
            "  if (uUseRipple == 1) {\n" +
            "    float feather = 0.18;\n" +
            "    float radialAlpha = 1.0 - smoothstep(uRippleRadius - feather, uRippleRadius + feather, dist);\n" +
            "    gl_FragColor = vec4(color.rgb, color.a * uAlpha * radialAlpha);\n" +
            "  } else {\n" +
            "    gl_FragColor = vec4(color.rgb, color.a * uAlpha);\n" +
            "  }\n" +
            "}\n";

    private final Context mContext;
    private final HandlerThread mGLThread;
    private final Handler mGLHandler;

    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;

    private int mProgramOES = 0;
    private int mProgram2D = 0;
    private int mVideoTexId = 0;
    private int mWallpaperTexId = 0;
    private int mAlbumArtTexId = 0;
    private int mQualityTexId = 0;
    private int mPrevArtTexId = 0;

    // Smooth Canvas Video Dissolve over Static Artwork (500ms ease-out)
    private static final long VIDEO_FADE_DURATION_MS = 500;
    private long mVideoFadeStartTime = 0;
    private boolean mIsVideoFadingIn = false;
    private float mVideoAlpha = 0.0f;

    // Smooth Track-to-Track Album Art Crossfade (350ms ease-out)
    private static final long ART_CROSSFADE_DURATION_MS = 350;
    private long mArtCrossfadeStartTime = 0;
    private boolean mIsArtCrossfading = false;
    private float mArtAlpha = 1.0f;

    private Bitmap mQualityBitmap = null;
    private Canvas mQualityCanvas = null;
    private String mLastQualityText = null;
    private final ByteBuffer mPixelBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder());
    private long mLastLuminanceSampleTime = 0;
    private long mLastCardDrawTime = 0;
    private boolean mIsBgLight = false;
    private boolean mNeedRedrawQuality = false;

    private SurfaceTexture mSurfaceTexture;
    private Surface mVideoInputSurface;
    private MediaPlayer mMediaPlayer;
    private FileInputStream mCurrentFis;

    private int mWidth = 1080;
    private int mHeight = 1920;
    private int mVideoWidth = 0;
    private int mVideoHeight = 0;

    private boolean mIsMediaPlaying = false;
    private boolean mIsVisible = false;
    private boolean mHasNewVideoFrame = false;
    private boolean mVideoIsReady = false;

    private String mActiveCanvasVideoPath = null;
    private String mEffectiveIdleVideoPath = null;
    private String mCurrentlyPlayingVideoPath = null;
    private Bitmap mIdleBitmap = null;
    private Bitmap mAlbumArtBitmap = null;
    private Bitmap mRawAlbumArtBitmap = null;
    private boolean mIsDisplayingAlbumArt = false;

    // Idle Video Control: Ensure it plays only once per wake session
    private boolean mIdleVideoHasCompleted = false;

    // Transition State: ColorOS Radial Ripple Shader Animation
    private static final long TRANSITION_DURATION_MS = 480;
    private long mTransitionStartTime = 0;
    private boolean mIsTransitioning = false;
    private boolean mTransitionFadeIn = true;
    private float mCurrentAlpha = 1.0f;
    private float mRippleRadius = 0.0f;
    private static final float BUTTON_POS_X = 0.592f;
    private static final float BUTTON_POS_Y = 0.077f; // Lockscreen bottom pill play/pause button (212px from bottom on 2760h)
    private static final float MAX_RIPPLE_RADIUS = 2.45f;

    private final float[] mSTMatrix = new float[16];
    private final float[] mIdentityMatrix = new float[16];

    private FloatBuffer mQuadVertices;
    private FloatBuffer mQuadTexCoords;

    public GLVideoWallpaperRenderer(Context context) {
        mContext = context.getApplicationContext();
        android.opengl.Matrix.setIdentityM(mIdentityMatrix, 0);

        float[] quadVertices = {
                -1.0f, -1.0f,
                 1.0f, -1.0f,
                -1.0f,  1.0f,
                 1.0f,  1.0f
        };
        mQuadVertices = ByteBuffer.allocateDirect(quadVertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(quadVertices);
        mQuadVertices.position(0);

        float[] quadTexCoords = {
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f
        };
        mQuadTexCoords = ByteBuffer.allocateDirect(quadTexCoords.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(quadTexCoords);
        mQuadTexCoords.position(0);

        mEffectiveIdleVideoPath = WallpaperBackupManager.getEffectiveIdleVideoPath(mContext);

        mGLThread = new HandlerThread("GLVideoRenderThread");
        mGLThread.start();
        mGLHandler = new Handler(mGLThread.getLooper());
    }

    public void onSurfaceCreated(SurfaceHolder holder) {
        mGLHandler.post(() -> initEGL(holder));
    }

    public void onSurfaceChanged(int width, int height) {
        mGLHandler.post(() -> {
            mWidth = width;
            mHeight = height;
            GLES20.glViewport(0, 0, width, height);
            if (mIsDisplayingAlbumArt && mAlbumArtBitmap != null) {
                if (mAlbumArtBitmap.getWidth() != mWidth || mAlbumArtBitmap.getHeight() != mHeight) {
                    Bitmap reformat = AlbumArtAestheticFraming.formatForLockscreen(mContext, mAlbumArtBitmap, mWidth, mHeight);
                    if (reformat != null) {
                        mAlbumArtBitmap = reformat;
                        updateAlbumArtTexture(mAlbumArtBitmap);
                    }
                }
            }
            renderCurrentState();
        });
    }

    public void onVisibilityChanged(boolean visible) {
        mIsVisible = visible;
        mGLHandler.post(() -> {
            Log.d(TAG, "Visibility: " + visible + " | isMediaPlaying: " + mIsMediaPlaying);
            if (visible) {
                // When screen wakes up, allow idle video to play its opening animation once
                mIdleVideoHasCompleted = false;
                resumeAppropriatePlayback();
            } else {
                if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                    try { mMediaPlayer.pause(); } catch (Exception ignored) {}
                }
            }
        });
    }

    public boolean isCanvasVideoPlaying() {
        return mIsMediaPlaying && mActiveCanvasVideoPath != null && mVideoIsReady;
    }

    public void playActiveCanvasVideo(String videoPath) {
        if (videoPath == null) {
            mActiveCanvasVideoPath = null;
            mGLHandler.post(() -> {
                stopVideoPlayback();
                mVideoIsReady = false;
                mVideoAlpha = 0.0f;
                mIsVideoFadingIn = false;
                if (mAlbumArtBitmap != null) {
                    mIsDisplayingAlbumArt = true;
                    updateAlbumArtTexture(mAlbumArtBitmap);
                }
                renderCurrentState();
            });
            return;
        }
        if (videoPath.equals(mActiveCanvasVideoPath) && mVideoIsReady && mMediaPlayer != null && mMediaPlayer.isPlaying()) {
            return;
        }
        mActiveCanvasVideoPath = videoPath;
        mIsMediaPlaying = true;
        // Don't re-trigger a second button ripple if we are already displaying media / transitioning
        boolean animateFromButton = !mIsDisplayingAlbumArt && !mVideoIsReady && !mIsTransitioning && mCurrentAlpha < 0.99f;
        mGLHandler.post(() -> {
            startVideoPlayback(videoPath, true, animateFromButton);
        });
    }

    public void displayAlbumArt(Bitmap albumArt) {
        if (albumArt == null) return;
        mIsMediaPlaying = true;
        mGLHandler.post(() -> {
            boolean wasAlreadyShowingMedia = (mAlbumArtBitmap != null || mVideoIsReady);
            mRawAlbumArtBitmap = albumArt;
            Bitmap formatted = albumArt;
            if (mWidth > 0 && mHeight > 0) {
                if (albumArt.getWidth() != mWidth || albumArt.getHeight() != mHeight) {
                    formatted = AlbumArtAestheticFraming.formatForLockscreen(mContext, albumArt, mWidth, mHeight);
                }
            }

            // Crossfade artwork if changing tracks while media was already showing
            if (wasAlreadyShowingMedia && mAlbumArtBitmap != null && !mAlbumArtBitmap.isRecycled() && mPrevArtTexId != 0) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mPrevArtTexId);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mAlbumArtBitmap, 0);
                mIsArtCrossfading = true;
                mArtAlpha = 0.0f;
                mArtCrossfadeStartTime = System.currentTimeMillis();
            } else {
                mIsArtCrossfading = false;
                mArtAlpha = 1.0f;
            }

            mAlbumArtBitmap = (formatted != null) ? formatted : albumArt;
            updateAlbumArtTexture(mAlbumArtBitmap);

            // Immediately stop old video playback so it doesn't linger during download
            mActiveCanvasVideoPath = null;
            stopVideoPlayback();
            mVideoIsReady = false;
            mVideoAlpha = 0.0f;
            mIsVideoFadingIn = false;
            mIsDisplayingAlbumArt = true;

            // Only trigger a new ripple from button if starting fresh from idle or if currently fading out
            if (!wasAlreadyShowingMedia || (mIsTransitioning && !mTransitionFadeIn) || mCurrentAlpha < 0.95f) {
                startTransitionToMedia();
            } else {
                renderCurrentState();
            }
        });
    }

    public void pausePlayback() {
        mIsMediaPlaying = false;
        mGLHandler.post(this::startFadeOutToIdle);
    }

    public void resumePlayback() {
        mIsMediaPlaying = true;
        mGLHandler.post(() -> {
            if (mActiveCanvasVideoPath != null) {
                // If we are not currently playing the active canvas video, or player isn't ready/playing, restart clean
                if (!mActiveCanvasVideoPath.equals(mCurrentlyPlayingVideoPath) || mMediaPlayer == null || !mVideoIsReady) {
                    startVideoPlayback(mActiveCanvasVideoPath, true, false);
                } else {
                    try {
                        if (!mMediaPlayer.isPlaying()) {
                            mMediaPlayer.start();
                        }
                        if (!mTransitionFadeIn || mCurrentAlpha < 0.99f) {
                            startTransitionToMedia();
                        } else {
                            renderCurrentState();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to start existing player on resume, restarting: " + e.getMessage());
                        startVideoPlayback(mActiveCanvasVideoPath, true, false);
                    }
                }
            } else if (mRawAlbumArtBitmap != null) {
                mIsDisplayingAlbumArt = true;
                stopVideoPlayback();
                updateAlbumArtTexture(mAlbumArtBitmap);
                if (!mTransitionFadeIn || mCurrentAlpha < 0.99f) {
                    startTransitionToMedia();
                } else {
                    renderCurrentState();
                }
            } else {
                resumeAppropriatePlayback();
            }
        });
    }

    private void startTransitionToMedia() {
        if (mIsTransitioning && mTransitionFadeIn) {
            return; // Already expanding, avoid duplicate ripple
        }
        if (!mIsTransitioning && mCurrentAlpha >= 0.99f && mTransitionFadeIn) {
            return; // Already fully transitioned to media, no duplicate ripple
        }
        mTransitionFadeIn = true;
        mTransitionStartTime = System.currentTimeMillis();
        mIsTransitioning = true;
        renderCurrentState();
    }

    public void reloadIdleConfiguration() {
        mGLHandler.post(() -> {
            mEffectiveIdleVideoPath = WallpaperBackupManager.getEffectiveIdleVideoPath(mContext);
            mIdleBitmap = WallpaperBackupManager.loadBackupBitmap(mContext);
            mIdleVideoHasCompleted = false;
            updateWallpaperTexture();
            if (!mIsMediaPlaying) {
                resumeAppropriatePlayback();
            }
        });
    }

    public void requestOverlayRedraw() {
        mNeedRedrawQuality = true;
        mGLHandler.post(this::renderCurrentState);
    }

    public void onDestroy() {
        mGLHandler.post(() -> {
            stopVideoPlayback();
            destroyEGL();
        });
        mGLThread.quitSafely();
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        mHasNewVideoFrame = true;
        mGLHandler.post(this::renderCurrentState);
    }

    private void resumeAppropriatePlayback() {
        if (mIsMediaPlaying) {
            if (mActiveCanvasVideoPath != null) {
                startVideoPlayback(mActiveCanvasVideoPath, true, false);
            } else if (mIsDisplayingAlbumArt && mAlbumArtBitmap != null) {
                stopVideoPlayback();
                updateAlbumArtTexture(mAlbumArtBitmap);
                renderCurrentState();
            } else {
                playIdleBackground(true);
            }
        } else {
            playIdleBackground(true);
        }
    }

    private void playIdleBackground(boolean allowVideoIntro) {
        boolean useVideo = allowVideoIntro && WallpaperBackupManager.isIdleVideoMode(mContext) && mEffectiveIdleVideoPath != null;
        if (useVideo) {
            // Check if already resting on final frame
            if (mIdleVideoHasCompleted && mEffectiveIdleVideoPath.equals(mCurrentlyPlayingVideoPath) && mMediaPlayer != null) {
                Log.d(TAG, "Idle video already completed. Resting on final frame.");
                return;
            }
            startVideoPlayback(mEffectiveIdleVideoPath, false, false);
        } else {
            stopVideoPlayback();
            mIdleVideoHasCompleted = true;
            renderCurrentState();
        }
    }

    private void startFadeOutToIdle() {
        if (mVideoIsReady || (mAlbumArtBitmap != null && mIsDisplayingAlbumArt)) {
            mTransitionFadeIn = false;
            mTransitionStartTime = System.currentTimeMillis();
            mIsTransitioning = true;
            renderCurrentState();
        } else {
            stopVideoPlayback();
            mIsDisplayingAlbumArt = false;
            playIdleBackground(false);
        }
    }

    private void initEGL(SurfaceHolder holder) {
        try {
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1);

            int[] attribList = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 0,
                    EGL14.EGL_STENCIL_SIZE, 0,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, 1, numConfigs, 0);

            int[] ctxAttribs = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);

            int[] surfaceAttribs = { EGL14.EGL_NONE };
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], holder, surfaceAttribs, 0);

            EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext);

            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            Matrix.setIdentityM(mSTMatrix, 0);

            initShaders();
            initTextures();

            Log.d(TAG, "EGL initialized successfully!");
            resumeAppropriatePlayback();
        } catch (Exception e) {
            Log.e(TAG, "EGL init failed: " + e.getMessage(), e);
        }
    }

    private void initShaders() {
        mProgramOES = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES);
        mProgram2D = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);
    }

    private void initTextures() {
        int[] tex = new int[5];
        GLES20.glGenTextures(5, tex, 0);
        mVideoTexId = tex[0];
        mWallpaperTexId = tex[1];
        mAlbumArtTexId = tex[2];
        mQualityTexId = tex[3];
        mPrevArtTexId = tex[4];

        // OES Video Texture
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mVideoTexId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        mSurfaceTexture = new SurfaceTexture(mVideoTexId);
        mSurfaceTexture.setOnFrameAvailableListener(this);
        mVideoInputSurface = new Surface(mSurfaceTexture);

        // 2D Wallpaper Texture
        setup2DTexture(mWallpaperTexId);

        // 2D Album Art Texture
        setup2DTexture(mAlbumArtTexId);

        // 2D Previous Album Art Texture (for seamless track crossfade)
        setup2DTexture(mPrevArtTexId);

        // 2D Playback Quality Overlay Texture
        setup2DTexture(mQualityTexId);

        mIdleBitmap = WallpaperBackupManager.loadBackupBitmap(mContext);
        updateWallpaperTexture();
    }

    private void setup2DTexture(int texId) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private void updateWallpaperTexture() {
        if (mIdleBitmap != null && !mIdleBitmap.isRecycled() && mWallpaperTexId != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mWallpaperTexId);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mIdleBitmap, 0);
        }
    }

    private void updateAlbumArtTexture(Bitmap bmp) {
        if (bmp != null && !bmp.isRecycled() && mAlbumArtTexId != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mAlbumArtTexId);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        }
    }

    private void startVideoPlayback(String videoPath, boolean loop, boolean animateTransition) {
        if (videoPath == null || !new File(videoPath).exists() || mVideoInputSurface == null) {
            Log.w(TAG, "Cannot start video, invalid path: " + videoPath);
            renderCurrentState();
            return;
        }

        stopVideoPlayback();
        mVideoIsReady = false;
        mCurrentlyPlayingVideoPath = videoPath;

        try {
            File vFile = new File(videoPath);
            mCurrentFis = new FileInputStream(vFile);

            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setDataSource(mCurrentFis.getFD(), 0, vFile.length());
            mMediaPlayer.setSurface(mVideoInputSurface);
            mMediaPlayer.setLooping(loop);
            mMediaPlayer.setVolume(0f, 0f);

            if (!loop) {
                mMediaPlayer.setOnCompletionListener(mp -> {
                    Log.d(TAG, "Idle video completed (played once). Resting permanently on final frame.");
                    mIdleVideoHasCompleted = true;
                });
            }

            mMediaPlayer.setOnPreparedListener(mp -> {
                mVideoWidth = mp.getVideoWidth();
                mVideoHeight = mp.getVideoHeight();
                mVideoIsReady = true;
                Log.d(TAG, "Video prepared: " + mVideoWidth + "x" + mVideoHeight + " (" + vFile.getName() + " | loop=" + loop + ")");

                // If album art is currently visible or available, fade video in smoothly on top of it!
                if (mIsDisplayingAlbumArt || mAlbumArtBitmap != null) {
                    mIsVideoFadingIn = true;
                    mVideoFadeStartTime = System.currentTimeMillis();
                    mVideoAlpha = 0.0f;
                } else {
                    mIsVideoFadingIn = false;
                    mVideoAlpha = 1.0f;
                }

                // Only start a ripple if starting from scratch, never restart on top of existing media transition
                if (animateTransition && !mIsDisplayingAlbumArt && !mIsTransitioning && mCurrentAlpha < 0.99f) {
                    mTransitionFadeIn = true;
                    mTransitionStartTime = System.currentTimeMillis();
                    mIsTransitioning = true;
                    mCurrentAlpha = 0.0f;
                } else if (!mIsTransitioning) {
                    mCurrentAlpha = 1.0f;
                    mRippleRadius = MAX_RIPPLE_RADIUS;
                }

                mp.start();
                renderCurrentState();
            });

            mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                stopVideoPlayback();
                renderCurrentState();
                return true;
            });

            mMediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Error starting video in OpenGL: " + e.getMessage(), e);
            stopVideoPlayback();
            renderCurrentState();
        }
    }

    private void stopVideoPlayback() {
        mVideoIsReady = false;
        mVideoAlpha = 0.0f;
        mIsVideoFadingIn = false;
        mCurrentlyPlayingVideoPath = null;
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) {
                    mMediaPlayer.stop();
                }
                mMediaPlayer.reset();
                mMediaPlayer.release();
            } catch (Exception ignored) {}
            mMediaPlayer = null;
        }
        if (mCurrentFis != null) {
            try { mCurrentFis.close(); } catch (Exception ignored) {}
            mCurrentFis = null;
        }
    }

    private void renderCurrentState() {
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY || mEGLSurface == EGL14.EGL_NO_SURFACE) {
            return;
        }

        boolean needsContinuousRender = false;

        // 1. Ripple / Main Transition Animation
        if (mIsTransitioning) {
            long elapsed = System.currentTimeMillis() - mTransitionStartTime;
            float t = (float) elapsed / (float) TRANSITION_DURATION_MS;
            if (t >= 1.0f) {
                t = 1.0f;
                mIsTransitioning = false;
                if (!mTransitionFadeIn) {
                    stopVideoPlayback();
                    mIsDisplayingAlbumArt = false;
                    playIdleBackground(false);
                    return;
                } else {
                    mCurrentAlpha = 1.0f;
                    mRippleRadius = MAX_RIPPLE_RADIUS;
                    if (mVideoIsReady && !mIsVideoFadingIn) {
                        mIsDisplayingAlbumArt = false;
                    }
                }
            } else {
                // Smooth cubic ease-out
                float ease = 1.0f - (float) Math.pow(1.0f - t, 3);
                mCurrentAlpha = mTransitionFadeIn ? ease : (1.0f - ease);
                mRippleRadius = mTransitionFadeIn ? (ease * MAX_RIPPLE_RADIUS) : ((1.0f - ease) * MAX_RIPPLE_RADIUS);
                needsContinuousRender = true;
            }
        } else if (!mVideoIsReady && !mIsDisplayingAlbumArt) {
            mCurrentAlpha = 0.0f;
            mRippleRadius = 0.0f;
        } else {
            mCurrentAlpha = 1.0f;
            mRippleRadius = MAX_RIPPLE_RADIUS;
        }

        // 2. Track-to-Track Artwork Crossfade Animation
        if (mIsArtCrossfading) {
            long elapsed = System.currentTimeMillis() - mArtCrossfadeStartTime;
            float t = (float) elapsed / (float) ART_CROSSFADE_DURATION_MS;
            if (t >= 1.0f) {
                mIsArtCrossfading = false;
                mArtAlpha = 1.0f;
            } else {
                // Cubic ease-out
                mArtAlpha = 1.0f - (float) Math.pow(1.0f - t, 3);
                needsContinuousRender = true;
            }
        } else {
            mArtAlpha = 1.0f;
        }

        // 3. Canvas Video Dissolve / Fade-In over Static Artwork
        if (mIsVideoFadingIn) {
            long elapsed = System.currentTimeMillis() - mVideoFadeStartTime;
            float t = (float) elapsed / (float) VIDEO_FADE_DURATION_MS;
            if (t >= 1.0f) {
                mIsVideoFadingIn = false;
                mVideoAlpha = 1.0f;
                mIsDisplayingAlbumArt = false;
            } else {
                // Cubic ease-out
                mVideoAlpha = 1.0f - (float) Math.pow(1.0f - t, 3);
                needsContinuousRender = true;
            }
        } else if (!mVideoIsReady) {
            mVideoAlpha = 0.0f;
        } else {
            mVideoAlpha = 1.0f;
        }

        if (needsContinuousRender || PlaybackQualityOverlay.isLyricAnimating()) {
            mGLHandler.postDelayed(this::renderCurrentState, 16);
        }

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        int useRipple = mIsTransitioning ? 1 : 0;

        // Base layer: Media artwork layer (or idle wallpaper if resting)
        boolean showMediaArt = (mAlbumArtBitmap != null && mAlbumArtTexId != 0) &&
                (mIsMediaPlaying || (mIsTransitioning && !mTransitionFadeIn && mCurrentAlpha > 0.01f));

        if (showMediaArt) {
            if (mIsArtCrossfading && mPrevArtTexId != 0) {
                // Previous track artwork underneath at full opacity
                render2DTexture(mPrevArtTexId, mWidth, mHeight, mCurrentAlpha, 0, 0f);
                // New track artwork dissolving smoothly on top
                render2DTexture(mAlbumArtTexId, mAlbumArtBitmap.getWidth(), mAlbumArtBitmap.getHeight(),
                        mCurrentAlpha * mArtAlpha, 0, 0f);
            } else if (mIsTransitioning) {
                render2DTexture(mWallpaperTexId,
                        (mIdleBitmap != null) ? mIdleBitmap.getWidth() : mWidth,
                        (mIdleBitmap != null) ? mIdleBitmap.getHeight() : mHeight,
                        1.0f, 0, 0f);
                render2DTexture(mAlbumArtTexId, mAlbumArtBitmap.getWidth(), mAlbumArtBitmap.getHeight(),
                        mCurrentAlpha, useRipple, mRippleRadius);
            } else {
                render2DTexture(mAlbumArtTexId, mAlbumArtBitmap.getWidth(), mAlbumArtBitmap.getHeight(),
                        1.0f, 0, 0f);
            }
        } else {
            // Idle state: Draw backup live wallpaper
            render2DTexture(mWallpaperTexId,
                    (mIdleBitmap != null) ? mIdleBitmap.getWidth() : mWidth,
                    (mIdleBitmap != null) ? mIdleBitmap.getHeight() : mHeight,
                    1.0f, 0, 0f);
        }

        // Active Canvas Video on top with smooth dissolve (effectiveAlpha)
        if (mVideoIsReady && mCurrentAlpha > 0.01f && mVideoAlpha > 0.01f) {
            float effectiveAlpha = mCurrentAlpha * mVideoAlpha;
            renderVideoFrame(effectiveAlpha, useRipple, mRippleRadius);
        }

        // Render real-time BitChord playback quality overlay directly above the bottom pill
        boolean showOverlay = (mIsMediaPlaying || (mIsTransitioning && !mTransitionFadeIn))
                && (mVideoIsReady || mIsDisplayingAlbumArt || mIsVideoFadingIn)
                && mCurrentAlpha > 0.05f;
        if (showOverlay) {
            renderPlaybackQualityOverlay(mCurrentAlpha);
            if (MediaPlaybackMonitorService.isNowPlayingCardEnabled(mContext) && mIsMediaPlaying && !mVideoIsReady) {
                mGLHandler.postDelayed(this::renderCurrentState, 350);
            }
        }

        EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
    }

    private void renderVideoFrame(float alpha, int useRipple, float rippleRadius) {
        try {
            if (mHasNewVideoFrame) {
                mSurfaceTexture.updateTexImage();
                mSurfaceTexture.getTransformMatrix(mSTMatrix);
                mHasNewVideoFrame = false;
            }

            GLES20.glUseProgram(mProgramOES);

            int posHandle = GLES20.glGetAttribLocation(mProgramOES, "aPosition");
            int texHandle = GLES20.glGetAttribLocation(mProgramOES, "aTexCoord");
            int stmHandle = GLES20.glGetUniformLocation(mProgramOES, "uSTMatrix");
            int smpHandle = GLES20.glGetUniformLocation(mProgramOES, "sTexture");
            int alpHandle = GLES20.glGetUniformLocation(mProgramOES, "uAlpha");
            int ripHandle = GLES20.glGetUniformLocation(mProgramOES, "uUseRipple");
            int radHandle = GLES20.glGetUniformLocation(mProgramOES, "uRippleRadius");
            int resHandle = GLES20.glGetUniformLocation(mProgramOES, "uResolution");
            int ctrHandle = GLES20.glGetUniformLocation(mProgramOES, "uRippleCenter");

            updateVideoVertices(mVideoWidth, mVideoHeight);

            GLES20.glEnableVertexAttribArray(posHandle);
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 8, mQuadVertices);

            GLES20.glEnableVertexAttribArray(texHandle);
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 8, mQuadTexCoords);

            GLES20.glUniformMatrix4fv(stmHandle, 1, false, mSTMatrix, 0);
            GLES20.glUniform1f(alpHandle, alpha);
            GLES20.glUniform1i(ripHandle, useRipple);
            GLES20.glUniform1f(radHandle, rippleRadius);
            GLES20.glUniform2f(resHandle, (float) mWidth, (float) mHeight);
            GLES20.glUniform2f(ctrHandle, BUTTON_POS_X, BUTTON_POS_Y);

            int topGradHandle = GLES20.glGetUniformLocation(mProgramOES, "uTopGradientEnabled");
            GLES20.glUniform1i(topGradHandle, MediaPlaybackMonitorService.isTopGradientBlendEnabled(mContext) ? 1 : 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mVideoTexId);
            GLES20.glUniform1i(smpHandle, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(posHandle);
            GLES20.glDisableVertexAttribArray(texHandle);
        } catch (Exception e) {
            Log.e(TAG, "Error rendering video frame: " + e.getMessage());
        }
    }

    private void render2DTexture(int texId, int contentW, int contentH, float alpha, int useRipple, float rippleRadius) {
        if (texId == 0) return;

        GLES20.glUseProgram(mProgram2D);

        int posHandle = GLES20.glGetAttribLocation(mProgram2D, "aPosition");
        int texHandle = GLES20.glGetAttribLocation(mProgram2D, "aTexCoord");
        int stmHandle = GLES20.glGetUniformLocation(mProgram2D, "uSTMatrix");
        int smpHandle = GLES20.glGetUniformLocation(mProgram2D, "sTexture");
        int alpHandle = GLES20.glGetUniformLocation(mProgram2D, "uAlpha");
        int ripHandle = GLES20.glGetUniformLocation(mProgram2D, "uUseRipple");
        int radHandle = GLES20.glGetUniformLocation(mProgram2D, "uRippleRadius");
        int resHandle = GLES20.glGetUniformLocation(mProgram2D, "uResolution");
        int ctrHandle = GLES20.glGetUniformLocation(mProgram2D, "uRippleCenter");

        updateCropVertices(contentW, contentH);

        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 8, mQuadVertices);

        float[] texCoords2D = {
                0.0f, 1.0f,
                1.0f, 1.0f,
                0.0f, 0.0f,
                1.0f, 0.0f
        };
        FloatBuffer tb = ByteBuffer.allocateDirect(texCoords2D.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords2D);
        tb.position(0);

        GLES20.glEnableVertexAttribArray(texHandle);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 8, tb);

        GLES20.glUniformMatrix4fv(stmHandle, 1, false, mIdentityMatrix, 0);
        GLES20.glUniform1f(alpHandle, alpha);
        GLES20.glUniform1i(ripHandle, useRipple);
        GLES20.glUniform1f(radHandle, rippleRadius);
        GLES20.glUniform2f(resHandle, (float) mWidth, (float) mHeight);
        GLES20.glUniform2f(ctrHandle, BUTTON_POS_X, BUTTON_POS_Y);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(smpHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
    }

    private void checkBackgroundLuminance() {
        long now = System.currentTimeMillis();
        if (now - mLastLuminanceSampleTime < 300) {
            return;
        }
        mLastLuminanceSampleTime = now;

        if (mWidth <= 0 || mHeight <= 0) return;

        // textX = mWidth * 0.50f; textY = mHeight * 0.862f (top-down origin)
        // In OpenGL coordinates (bottom-up origin):
        int sampleX = Math.max(0, mWidth / 2 - 2);
        int sampleY = Math.max(0, (int) (mHeight * (1.0f - 0.862f)) - 2);

        try {
            mPixelBuffer.position(0);
            GLES20.glReadPixels(sampleX, sampleY, 4, 4, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mPixelBuffer);

            float totalLum = 0.0f;
            for (int i = 0; i < 16; i++) {
                int r = mPixelBuffer.get(i * 4) & 0xFF;
                int g = mPixelBuffer.get(i * 4 + 1) & 0xFF;
                int b = mPixelBuffer.get(i * 4 + 2) & 0xFF;
                totalLum += (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f;
            }
            float avgLum = totalLum / 16.0f;

            // Hysteresis: Light if > 0.55, Dark if < 0.42 to avoid rapid jitter
            boolean newLight = mIsBgLight;
            if (avgLum > 0.55f) {
                newLight = true;
            } else if (avgLum < 0.42f) {
                newLight = false;
            }

            if (newLight != mIsBgLight) {
                mIsBgLight = newLight;
                mNeedRedrawQuality = true;
            }
        } catch (Throwable ignored) {}
    }

    private void renderPlaybackQualityOverlay(float alpha) {
        if (mWidth <= 0 || mHeight <= 0) return;

        PlaybackQualityOverlay.initFonts(mContext);
        String currentQuality = PlaybackQualityOverlay.getQualityText(mContext);
        if (currentQuality == null || currentQuality.isEmpty()) return;

        boolean isCardEnabled = MediaPlaybackMonitorService.isNowPlayingCardEnabled(mContext);
        long now = System.currentTimeMillis();

        String lyric = null;
        MediaPlaybackMonitorService.PlaybackSnapshot snapshot = null;
        if (isCardEnabled) {
            snapshot = MediaPlaybackMonitorService.getPlaybackSnapshot();
            long currentPos = snapshot.positionMs;
            if (snapshot.isPlaying && snapshot.lastPositionUpdateTime > 0) {
                long elapsed = android.os.SystemClock.elapsedRealtime() - snapshot.lastPositionUpdateTime;
                currentPos += (long) (elapsed * (snapshot.playbackSpeed > 0 ? snapshot.playbackSpeed : 1.0f));
            }
            lyric = SyncedLyricsManager.getCurrentLyric(currentPos);
        }

        boolean isAnimating = isCardEnabled && PlaybackQualityOverlay.isLyricAnimating();
        boolean lyricChanged = isCardEnabled && PlaybackQualityOverlay.hasLyricChanged(lyric);
        boolean cardTick = isCardEnabled && (now - mLastCardDrawTime > 250);

        // Recreate overlay bitmap if text changed, lyric changed, card tick elapsed, animation active, or bitmap not ready
        boolean needsRedraw = (mQualityBitmap == null || mQualityBitmap.getWidth() != mWidth ||
                               mQualityBitmap.getHeight() != mHeight ||
                               !currentQuality.equals(mLastQualityText) ||
                               lyricChanged ||
                               isAnimating ||
                               cardTick ||
                               mNeedRedrawQuality);

        if (needsRedraw) {
            if (mQualityBitmap == null || mQualityBitmap.getWidth() != mWidth || mQualityBitmap.getHeight() != mHeight) {
                if (mQualityBitmap != null && !mQualityBitmap.isRecycled()) {
                    mQualityBitmap.recycle();
                }
                mQualityBitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
                mQualityCanvas = new Canvas(mQualityBitmap);
            } else {
                mQualityCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            }
            mLastQualityText = currentQuality;
            mNeedRedrawQuality = false;
            mLastCardDrawTime = now;

            if (isCardEnabled) {
                PlaybackQualityOverlay.drawBitChordCard(mQualityCanvas, mWidth, mHeight, snapshot, lyric, currentQuality);
            } else {
                // Draw quality text cleanly in light color without dynamic changing
                PlaybackQualityOverlay.drawQualityPill(mQualityCanvas, mWidth, mHeight, currentQuality);
            }

            // Upload to GPU texture
            if (mQualityTexId != 0) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mQualityTexId);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mQualityBitmap, 0);
            }
        }

        if (mQualityTexId == 0) return;

        renderOverlayQuad(mQualityTexId, alpha);
    }

    private void renderOverlayQuad(int texId, float alpha) {
        if (texId == 0) return;

        GLES20.glUseProgram(mProgram2D);

        int posHandle = GLES20.glGetAttribLocation(mProgram2D, "aPosition");
        int texHandle = GLES20.glGetAttribLocation(mProgram2D, "aTexCoord");
        int stmHandle = GLES20.glGetUniformLocation(mProgram2D, "uSTMatrix");
        int smpHandle = GLES20.glGetUniformLocation(mProgram2D, "sTexture");
        int alpHandle = GLES20.glGetUniformLocation(mProgram2D, "uAlpha");
        int ripHandle = GLES20.glGetUniformLocation(mProgram2D, "uUseRipple");
        int radHandle = GLES20.glGetUniformLocation(mProgram2D, "uRippleRadius");
        int resHandle = GLES20.glGetUniformLocation(mProgram2D, "uResolution");
        int ctrHandle = GLES20.glGetUniformLocation(mProgram2D, "uRippleCenter");

        float[] fullQuad = {
                -1.0f, -1.0f,
                 1.0f, -1.0f,
                -1.0f,  1.0f,
                 1.0f,  1.0f
        };
        FloatBuffer qb = ByteBuffer.allocateDirect(fullQuad.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(fullQuad);
        qb.position(0);

        float[] texCoords2D = {
                0.0f, 1.0f,
                1.0f, 1.0f,
                0.0f, 0.0f,
                1.0f, 0.0f
        };
        FloatBuffer tb = ByteBuffer.allocateDirect(texCoords2D.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords2D);
        tb.position(0);

        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 8, qb);

        GLES20.glEnableVertexAttribArray(texHandle);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 8, tb);

        GLES20.glUniformMatrix4fv(stmHandle, 1, false, mIdentityMatrix, 0);
        GLES20.glUniform1f(alpHandle, alpha);
        GLES20.glUniform1i(ripHandle, 0);
        GLES20.glUniform1f(radHandle, 0f);
        GLES20.glUniform2f(resHandle, (float) mWidth, (float) mHeight);
        GLES20.glUniform2f(ctrHandle, BUTTON_POS_X, BUTTON_POS_Y);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(smpHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
    }

    private void updateVideoVertices(int contentWidth, int contentHeight) {
        if (contentWidth <= 0 || contentHeight <= 0 || mWidth <= 0 || mHeight <= 0) {
            float[] defaultQuad = { -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f };
            mQuadVertices.position(0);
            mQuadVertices.put(defaultQuad);
            mQuadVertices.position(0);
            return;
        }

        // Width exactly fits the screen (NDC x in [-1.0, 1.0])
        // Aligned to bottom (NDC y starts at -1.0)
        float screenRatio = (float) mWidth / (float) mHeight;
        float contentRatio = (float) contentWidth / (float) contentHeight;

        // Video height in NDC maintaining content aspect ratio when width fills screen (width NDC = 2.0f)
        float ndcHeight = 2.0f * (screenRatio / contentRatio);
        float bottom = -1.0f;
        float top = bottom + ndcHeight;

        float[] quad = {
                -1.0f, bottom,
                 1.0f, bottom,
                -1.0f, top,
                 1.0f, top
        };
        mQuadVertices.position(0);
        mQuadVertices.put(quad);
        mQuadVertices.position(0);
    }

    private void updateCropVertices(int contentWidth, int contentHeight) {
        if (contentWidth <= 0 || contentHeight <= 0 || mWidth <= 0 || mHeight <= 0) {
            float[] defaultQuad = { -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f };
            mQuadVertices.position(0);
            mQuadVertices.put(defaultQuad);
            mQuadVertices.position(0);
            return;
        }

        float screenRatio = (float) mWidth / (float) mHeight;
        float contentRatio = (float) contentWidth / (float) contentHeight;

        float scaleX = 1.0f;
        float scaleY = 1.0f;

        if (contentRatio > screenRatio) {
            scaleX = contentRatio / screenRatio;
        } else {
            scaleY = screenRatio / contentRatio;
        }

        float[] quad = {
                -scaleX, -scaleY,
                 scaleX, -scaleY,
                -scaleX,  scaleY,
                 scaleX,  scaleY
        };
        mQuadVertices.position(0);
        mQuadVertices.put(quad);
        mQuadVertices.position(0);
    }

    private void destroyEGL() {
        if (mQualityBitmap != null && !mQualityBitmap.isRecycled()) {
            mQualityBitmap.recycle();
            mQualityBitmap = null;
        }
        if (mSurfaceTexture != null) {
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mVideoInputSurface != null) {
            mVideoInputSurface.release();
            mVideoInputSurface = null;
        }
        int[] tex = { mVideoTexId, mWallpaperTexId, mAlbumArtTexId, mQualityTexId, mPrevArtTexId };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mVideoTexId = 0;
        mWallpaperTexId = 0;
        mAlbumArtTexId = 0;
        mQualityTexId = 0;
        mPrevArtTexId = 0;
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (mEGLSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
                mEGLSurface = EGL14.EGL_NO_SURFACE;
            }
            if (mEGLContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
                mEGLContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(mEGLDisplay);
            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        return program;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }
}

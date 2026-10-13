package com.odialer.videoringtone;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Hardware-accelerated Video Processor for Oppo Find X9 / ColorOS 16 InCallUI.
 *
 * Pre-processes video ringtones to ensure optimal legibility and presentation:
 *  1. Progressive Top & Bottom Darkening / Vignette:
 *     Guarantees contact name/number and ColorOS answer/decline sliders are 100% visible.
 *  2. Display Modes:
 *     - Full Screen Fill (Center Crop to 9:16 target)
 *     - Ambient Blur Card (Floating rounded squircle card with dynamic ambient blurred backlight)
 *
 * Runs via MediaExtractor -> MediaCodec Decoder -> OpenGL ES 2.0 Surface -> MediaCodec Encoder -> MediaMuxer.
 * Preserves the original audio track without re-encoding.
 */
public class VideoProcessor {
    private static final String TAG = "VideoProcessor";

    public static final int MODE_FULL_SCREEN = 0;
    public static final int MODE_AMBIENT_CARD = 1;

    public static final int TARGET_WIDTH = 1080;
    public static final int TARGET_HEIGHT = 1920;
    public static final int TARGET_BITRATE = 4_500_000; // 4.5 Mbps
    public static final int TARGET_FRAME_RATE = 30;
    public static final int TARGET_I_FRAME_INTERVAL = 1; // 1s keyframe interval

    public interface ProgressCallback {
        void onProgress(int percent);
        void onComplete(File outputFile);
        void onError(Exception error);
    }

    public static class ProcessingOptions {
        public boolean enableVignette = true;
        public int displayMode = MODE_FULL_SCREEN; // or MODE_AMBIENT_CARD
        public float vignetteTopStrength = 0.70f;
        public float vignetteBottomStrength = 0.75f;
        public float cardCornerRadius = 0.045f; // Normalized GL radius
        public float cardWidthRatio = 0.88f;   // Width of card relative to screen
    }

    /**
     * Asynchronously process a video.
     */
    public static void processVideoAsync(
            Context context,
            Uri inputUri,
            File outputFile,
            ProcessingOptions options,
            ProgressCallback callback) {

        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                processVideoSync(context, inputUri, outputFile, options, percent -> {
                    if (callback != null) {
                        mainHandler.post(() -> callback.onProgress(percent));
                    }
                });
                if (callback != null) {
                    mainHandler.post(() -> callback.onComplete(outputFile));
                }
            } catch (Exception e) {
                Log.e(TAG, "Video processing failed", e);
                if (outputFile.exists()) {
                    outputFile.delete();
                }
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(e));
                }
            }
        }, "VideoProcessorThread").start();
    }

    /**
     * Synchronously process the video.
     */
    public static void processVideoSync(
            Context context,
            Uri inputUri,
            File outputFile,
            ProcessingOptions options,
            IntConsumer progressListener) throws Exception {

        if (options == null) options = new ProcessingOptions();

        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        try {
            videoExtractor.setDataSource(context, inputUri, null);
            audioExtractor.setDataSource(context, inputUri, null);
        } catch (Exception e) {
            videoExtractor.release();
            audioExtractor.release();
            throw new IOException("Failed to open source video: " + e.getMessage(), e);
        }

        int videoTrackIndex = -1;
        int audioTrackIndex = -1;
        MediaFormat inputVideoFormat = null;
        MediaFormat inputAudioFormat = null;

        for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
            MediaFormat format = videoExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/") && videoTrackIndex < 0) {
                videoTrackIndex = i;
                inputVideoFormat = format;
            } else if (mime != null && mime.startsWith("audio/") && audioTrackIndex < 0) {
                audioTrackIndex = i;
                inputAudioFormat = format;
            }
        }

        if (videoTrackIndex < 0) {
            videoExtractor.release();
            audioExtractor.release();
            throw new IllegalArgumentException("No video track found in input media");
        }

        videoExtractor.selectTrack(videoTrackIndex);
        if (audioTrackIndex >= 0) {
            audioExtractor.selectTrack(audioTrackIndex);
        }

        // Determine input dimensions & rotation
        int inWidth = inputVideoFormat.getInteger(MediaFormat.KEY_WIDTH);
        int inHeight = inputVideoFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int rotation = 0;
        if (inputVideoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            rotation = inputVideoFormat.getInteger(MediaFormat.KEY_ROTATION);
        } else {
            try {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                mmr.setDataSource(context, inputUri);
                String rotStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rotStr != null) {
                    rotation = Integer.parseInt(rotStr);
                }
                mmr.release();
            } catch (Throwable ignored) {
            }
        }

        long totalDurationUs = 0;
        if (inputVideoFormat.containsKey(MediaFormat.KEY_DURATION)) {
            totalDurationUs = inputVideoFormat.getLong(MediaFormat.KEY_DURATION);
        }

        // Configure MediaMuxer
        if (outputFile.exists()) {
            outputFile.delete();
        }
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        MediaMuxer muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Configure Video Encoder
        MediaFormat outVideoFormat = MediaFormat.createVideoFormat("video/avc", TARGET_WIDTH, TARGET_HEIGHT);
        outVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        outVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BITRATE);
        outVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FRAME_RATE);
        outVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, TARGET_I_FRAME_INTERVAL);

        MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
        encoder.configure(outVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encoderInputSurface = encoder.createInputSurface();
        encoder.start();

        // OpenGL ES pipeline
        CodeSurfaceRenderer renderer = new CodeSurfaceRenderer(
                encoderInputSurface,
                inWidth, inHeight, rotation,
                TARGET_WIDTH, TARGET_HEIGHT,
                options);

        // Configure Video Decoder
        String videoMime = inputVideoFormat.getString(MediaFormat.KEY_MIME);
        MediaCodec decoder = MediaCodec.createDecoderByType(videoMime);
        decoder.configure(inputVideoFormat, renderer.getDecoderSurface(), null, 0);
        decoder.start();

        int muxerVideoTrack = -1;
        int muxerAudioTrack = -1;
        boolean muxerStarted = false;

        if (audioTrackIndex >= 0 && inputAudioFormat != null) {
            muxerAudioTrack = muxer.addTrack(inputAudioFormat);
        }

        boolean decoderDone = false;
        boolean encoderDone = false;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        final long TIMEOUT_US = 10_000;

        try {
            while (!encoderDone) {
                // Feed decoder
                if (!decoderDone) {
                    int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            int sampleSize = videoExtractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                decoderDone = true;
                            } else {
                                long presentationTimeUs = videoExtractor.getSampleTime();
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0);
                                videoExtractor.advance();
                            }
                        }
                    }
                }

                // Drain decoder & render to OpenGL surface
                int outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (outIndex >= 0) {
                    boolean doRender = (bufferInfo.size != 0);
                    decoder.releaseOutputBuffer(outIndex, doRender);
                    if (doRender) {
                        renderer.awaitNewImage();
                        renderer.drawImage(bufferInfo.presentationTimeUs * 1000L); // nanoseconds
                    }
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.signalEndOfInputStream();
                    }
                }

                // Drain encoder
                int encIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new RuntimeException("Encoder format changed twice");
                    }
                    MediaFormat newFormat = encoder.getOutputFormat();
                    muxerVideoTrack = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                } else if (encIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(encIndex);
                    if (encodedData == null) {
                        throw new RuntimeException("Encoder output buffer " + encIndex + " was null");
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }

                    if (bufferInfo.size != 0) {
                        if (!muxerStarted) {
                            throw new RuntimeException("Muxer hasn't started");
                        }
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo);

                        if (totalDurationUs > 0 && progressListener != null) {
                            int pct = (int) Math.min(95, (bufferInfo.presentationTimeUs * 95L) / totalDurationUs);
                            progressListener.accept(pct);
                        }
                    }

                    encoder.releaseOutputBuffer(encIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true;
                    }
                }
            }

            // Copy audio track directly to muxer if present
            if (audioTrackIndex >= 0 && muxerAudioTrack >= 0 && muxerStarted) {
                ByteBuffer audioBuffer = ByteBuffer.allocateDirect(256 * 1024);
                MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
                while (true) {
                    audioInfo.offset = 0;
                    audioInfo.size = audioExtractor.readSampleData(audioBuffer, 0);
                    if (audioInfo.size < 0) {
                        break;
                    }
                    audioInfo.presentationTimeUs = audioExtractor.getSampleTime();
                    audioInfo.flags = audioExtractor.getSampleFlags();
                    muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo);
                    audioExtractor.advance();
                }
            }

            if (progressListener != null) {
                progressListener.accept(100);
            }

        } finally {
            try {
                decoder.stop();
                decoder.release();
            } catch (Throwable ignored) {}

            try {
                encoder.stop();
                encoder.release();
            } catch (Throwable ignored) {}

            try {
                renderer.release();
            } catch (Throwable ignored) {}

            try {
                if (muxerStarted) {
                    muxer.stop();
                }
                muxer.release();
            } catch (Throwable ignored) {}

            videoExtractor.release();
            audioExtractor.release();
        }
    }

    public interface IntConsumer {
        void accept(int value);
    }

    // =========================================================================
    // OPENGL ES 2.0 SURFACE RENDERER
    // =========================================================================

    private static class CodeSurfaceRenderer implements SurfaceTexture.OnFrameAvailableListener {
        private final Surface mEncoderSurface;
        private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;

        private SurfaceTexture mSurfaceTexture;
        private Surface mDecoderSurface;
        private int mTextureId = -1;

        private int mProgram = 0;
        private int maPositionHandle = -1;
        private int maTextureHandle = -1;
        private int muMVPMatrixHandle = -1;
        private int muSTMatrixHandle = -1;

        // Custom Uniforms
        private int muModeHandle = -1;
        private int muEnableVignetteHandle = -1;
        private int muVignetteTopHandle = -1;
        private int muVignetteBottomHandle = -1;
        private int muCardParamsHandle = -1;
        private int muAspectHandle = -1;

        private final float[] mMVPMatrix = new float[16];
        private final float[] mSTMatrix = new float[16];

        private FloatBuffer mTriangleVertices;
        private boolean mFrameAvailable = false;
        private final Object mFrameSyncObject = new Object();

        private final int mTargetWidth;
        private final int mTargetHeight;
        private final ProcessingOptions mOptions;

        private float mSrcAspect = 1.0f;

        private static final int FLOAT_SIZE_BYTES = 4;
        private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
        private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
        private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;

        private static final float[] FULL_QUAD_DATA = {
                // X,     Y,    Z,    U,    V
                -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
                 1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f, 0.0f, 1.0f,
                 1.0f,  1.0f, 0.0f, 1.0f, 1.0f,
        };

        public CodeSurfaceRenderer(
                Surface encoderSurface,
                int inWidth, int inHeight, int rotation,
                int targetWidth, int targetHeight,
                ProcessingOptions options) {

            mEncoderSurface = encoderSurface;
            mTargetWidth = targetWidth;
            mTargetHeight = targetHeight;
            mOptions = options;

            // Handle rotation for aspect ratio calculation
            int effWidth = (rotation == 90 || rotation == 270) ? inHeight : inWidth;
            int effHeight = (rotation == 90 || rotation == 270) ? inWidth : inHeight;
            mSrcAspect = (effHeight > 0) ? ((float) effWidth / (float) effHeight) : (9.0f / 16.0f);

            mTriangleVertices = ByteBuffer.allocateDirect(FULL_QUAD_DATA.length * FLOAT_SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTriangleVertices.put(FULL_QUAD_DATA).position(0);

            Matrix.setIdentityM(mMVPMatrix, 0);

            eglSetup();
            makeCurrent();
            setupGl();
        }

        public Surface getDecoderSurface() {
            return mDecoderSurface;
        }

        private void eglSetup() {
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("unable to get EGL14 display");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
                throw new RuntimeException("unable to initialize EGL14");
            }

            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    0x3142, 1, // EGL_RECORDABLE_ANDROID
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);
            if (numConfigs[0] == 0) {
                // Fallback without recordable bit
                int[] attribListFallback = {
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_NONE
                };
                EGL14.eglChooseConfig(mEGLDisplay, attribListFallback, 0, configs, 0, configs.length, numConfigs, 0);
            }

            int[] attrib_list = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, attrib_list, 0);

            int[] surfaceAttribs = {
                    EGL14.EGL_NONE
            };
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mEncoderSurface, surfaceAttribs, 0);
        }

        private void makeCurrent() {
            EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext);
        }

        private void setupGl() {
            final String VERTEX_SHADER =
                    "uniform mat4 uMVPMatrix;\n" +
                    "uniform mat4 uSTMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "varying vec2 vPos;\n" +
                    "void main() {\n" +
                    "    gl_Position = uMVPMatrix * aPosition;\n" +
                    "    vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                    "    vPos = aPosition.xy;\n" +
                    "}\n";

            final String FRAGMENT_SHADER =
                    "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "varying vec2 vPos;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "uniform mat4 uSTMatrix;\n" +
                    "uniform int uMode;\n" +               // 0 = Full screen, 1 = Ambient Card
                    "uniform int uEnableVignette;\n" +      // 1 = enabled, 0 = disabled
                    "uniform float uVignetteTop;\n" +        // 0.70
                    "uniform float uVignetteBottom;\n" +     // 0.75
                    "uniform vec4 uCardParams;\n" +         // [cardHalfW, cardHalfH, cornerRadius, screenAspect]
                    "\n" +
                    "// Isotropic Rounded Box Signed Distance Function\n" +
                    "float roundedBoxSDF(vec2 p, vec2 b, float r, float aspect) {\n" +
                    "    vec2 pIso = vec2(p.x * aspect, p.y);\n" +
                    "    vec2 bIso = vec2(b.x * aspect, b.y);\n" +
                    "    vec2 q = abs(pIso) - bIso + vec2(r);\n" +
                    "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n" +
                    "}\n" +
                    "\n" +
                    "// 9-tap blurred sample around coordinate\n" +
                    "vec4 sampleBlurred(vec2 coord) {\n" +
                    "    vec4 sum = vec4(0.0);\n" +
                    "    float sX = 0.035;\n" +
                    "    float sY = 0.035;\n" +
                    "    sum += texture2D(sTexture, clamp(coord + vec2(-sX, -sY), 0.01, 0.99)) * 0.07;\n" +
                    "    sum += texture2D(sTexture, clamp(coord + vec2( 0.0, -sY), 0.01, 0.99)) * 0.12;\n" +
                    "    sum += texture2D(sTexture, clamp(coord + vec2(  sX, -sY), 0.01, 0.99)) * 0.07;\n" +
                    "    sum += texture2D(sTexture, clamp(coord + vec2(-sX,  0.0), 0.01, 0.99)) * 0.12;\n" +
                    "    sum += texture2D(sTexture, clamp(coord                  , 0.01, 0.99)) * 0.24;\n" +
                    "    sum += texture2D(sTexture, clamp(coord + vec2(  sX,  0.0), 0.01, 0.99)) * 0.12;\n" +
                    "    sum += texture2D(sTexture, clamp(coord + vec2(-sX,   sY), 0.01, 0.99)) * 0.07;\n" +
                    "    sum += texture2D(sTexture, clamp(coord + vec2( 0.0,   sY), 0.01, 0.99)) * 0.12;\n" +
                    "    sum += texture2D(sTexture, clamp(coord + vec2(  sX,   sY), 0.01, 0.99)) * 0.07;\n" +
                    "    return sum;\n" +
                    "}\n" +
                    "\n" +
                    "void main() {\n" +
                    "    vec4 baseColor;\n" +
                    "\n" +
                    "    if (uMode == 1) {\n" +
                    "        // AMBIENT BLUR CARD MODE\n" +
                    "        vec2 cardHalf = uCardParams.xy;\n" +
                    "        float cornerR = uCardParams.z;\n" +
                    "        float aspect = uCardParams.w;\n" +
                    "        float dist = roundedBoxSDF(vPos, cardHalf, cornerR, aspect);\n" +
                    "\n" +
                    "        // Background: zoomed ambient blurred backlight\n" +
                    "        vec2 bgCoord = (vTextureCoord - 0.5) * 0.88 + 0.5;\n" +
                    "        vec4 ambientBg = sampleBlurred(bgCoord);\n" +
                    "        ambientBg.rgb *= 0.58; // Elegant dimming for backlight glow\n" +
                    "\n" +
                    "        // Soft drop-shadow behind card\n" +
                    "        float shadowAlpha = clamp(1.0 - smoothstep(0.0, 0.06, dist), 0.0, 1.0) * 0.45;\n" +
                    "        vec4 compBg = mix(ambientBg, vec4(0.0, 0.0, 0.0, 1.0), shadowAlpha);\n" +
                    "\n" +
                    "        // Card content: map vPos within card boundaries\n" +
                    "        vec2 rawCardUv = (vPos + cardHalf) / (cardHalf * 2.0);\n" +
                    "        // Apply SurfaceTexture transformation matrix so orientation & framing are preserved\n" +
                    "        vec2 stCardUv = (uSTMatrix * vec4(rawCardUv, 0.0, 1.0)).xy;\n" +
                    "        vec4 cardContent = texture2D(sTexture, stCardUv);\n" +
                    "\n" +
                    "        // Antialiased card edge\n" +
                    "        float cardAlpha = clamp(1.0 - smoothstep(-0.005, 0.003, dist), 0.0, 1.0);\n" +
                    "        baseColor = mix(compBg, cardContent, cardAlpha);\n" +
                    "    } else {\n" +
                    "        // FULL SCREEN FILL MODE (Center-Crop)\n" +
                    "        baseColor = texture2D(sTexture, vTextureCoord);\n" +
                    "    }\n" +
                    "\n" +
                    "    // LEGIBILITY GRADIENT OVERLAYS (Top & Bottom progressive darkening)\n" +
                    "    if (uEnableVignette == 1) {\n" +
                    "        // Convert vPos.y (-1 to 1) to normalized screen Y (0 = bottom, 1 = top)\n" +
                    "        float screenY = (vPos.y + 1.0) * 0.5;\n" +
                    "\n" +
                    "        // Top 28% gradient (Caller name, phone number, status bar)\n" +
                    "        float topVignette = smoothstep(0.70, 0.96, screenY) * uVignetteTop;\n" +
                    "\n" +
                    "        // Bottom 28% gradient (ColorOS answer/decline sliders, hangup controls)\n" +
                    "        float botVignette = smoothstep(0.30, 0.04, screenY) * uVignetteBottom;\n" +
                    "\n" +
                    "        float totalVignette = clamp(topVignette + botVignette, 0.0, 0.90);\n" +
                    "        baseColor.rgb = mix(baseColor.rgb, vec3(0.0), totalVignette);\n" +
                    "    }\n" +
                    "\n" +
                    "    gl_FragColor = baseColor;\n" +
                    "}\n";

            mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
            muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
            muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");

            muModeHandle = GLES20.glGetUniformLocation(mProgram, "uMode");
            muEnableVignetteHandle = GLES20.glGetUniformLocation(mProgram, "uEnableVignette");
            muVignetteTopHandle = GLES20.glGetUniformLocation(mProgram, "uVignetteTop");
            muVignetteBottomHandle = GLES20.glGetUniformLocation(mProgram, "uVignetteBottom");
            muCardParamsHandle = GLES20.glGetUniformLocation(mProgram, "uCardParams");

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            mTextureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            mSurfaceTexture = new SurfaceTexture(mTextureId);
            mSurfaceTexture.setOnFrameAvailableListener(this);
            mDecoderSurface = new Surface(mSurfaceTexture);
        }

        public void awaitNewImage() {
            final int TIMEOUT_MS = 2500;
            synchronized (mFrameSyncObject) {
                while (!mFrameAvailable) {
                    try {
                        mFrameSyncObject.wait(TIMEOUT_MS);
                        if (!mFrameAvailable) {
                            throw new RuntimeException("Surface frame wait timed out");
                        }
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(ie);
                    }
                }
                mFrameAvailable = false;
            }
            mSurfaceTexture.updateTexImage();
            mSurfaceTexture.getTransformMatrix(mSTMatrix);
        }

        public void drawImage(long presentationTimeNs) {
            GLES20.glViewport(0, 0, mTargetWidth, mTargetHeight);
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(mProgram);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
            GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            GLES20.glEnableVertexAttribArray(maPositionHandle);

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            GLES20.glEnableVertexAttribArray(maTextureHandle);

            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

            // Pass Options
            GLES20.glUniform1i(muModeHandle, mOptions.displayMode);
            GLES20.glUniform1i(muEnableVignetteHandle, mOptions.enableVignette ? 1 : 0);
            GLES20.glUniform1f(muVignetteTopHandle, mOptions.vignetteTopStrength);
            GLES20.glUniform1f(muVignetteBottomHandle, mOptions.vignetteBottomStrength);

            // Card Geometry: calculate halfW, halfH based on source aspect ratio
            float targetAspect = (float) mTargetWidth / (float) mTargetHeight; // 9:16 = 0.5625
            float cardHalfW = mOptions.cardWidthRatio; // e.g. 0.88 in clip space
            // Calculate card half-height respecting video aspect ratio in screen space
            // In clip space: x is [-1, 1], y is [-1, 1].
            // Aspect ratio in pixels: (halfW * targetWidth) / (halfH * targetHeight) = mSrcAspect
            // => halfH = (halfW * targetAspect) / mSrcAspect
            float cardHalfH = (cardHalfW * targetAspect) / Math.max(0.1f, mSrcAspect);
            // Cap height so it fits nicely on screen
            cardHalfH = Math.min(cardHalfH, 0.58f);

            GLES20.glUniform4f(muCardParamsHandle, cardHalfW, cardHalfH, mOptions.cardCornerRadius, targetAspect);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, presentationTimeNs);
            EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            synchronized (mFrameSyncObject) {
                if (mFrameAvailable) {
                    Log.w(TAG, "mFrameAvailable already set, frame could be dropped");
                }
                mFrameAvailable = true;
                mFrameSyncObject.notifyAll();
            }
        }

        public void release() {
            if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
                EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(mEGLDisplay);
            }
            if (mDecoderSurface != null) {
                mDecoderSurface.release();
            }
            if (mSurfaceTexture != null) {
                mSurfaceTexture.release();
            }
            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
            mEGLContext = EGL14.EGL_NO_CONTEXT;
            mEGLSurface = EGL14.EGL_NO_SURFACE;
        }

        private static int loadShader(int shaderType, String source) {
            int shader = GLES20.glCreateShader(shaderType);
            if (shader != 0) {
                GLES20.glShaderSource(shader, source);
                GLES20.glCompileShader(shader);
                int[] compiled = new int[1];
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
                if (compiled[0] == 0) {
                    String info = GLES20.glGetShaderInfoLog(shader);
                    GLES20.glDeleteShader(shader);
                    throw new RuntimeException("Could not compile shader " + shaderType + ": " + info);
                }
            }
            return shader;
        }

        private static int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int program = GLES20.glCreateProgram();
            if (program != 0) {
                GLES20.glAttachShader(program, vertexShader);
                GLES20.glAttachShader(program, pixelShader);
                GLES20.glLinkProgram(program);
                int[] linkStatus = new int[1];
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
                if (linkStatus[0] != GLES20.GL_TRUE) {
                    String info = GLES20.glGetProgramInfoLog(program);
                    GLES20.glDeleteProgram(program);
                    throw new RuntimeException("Could not link program: " + info);
                }
            }
            return program;
        }
    }
}

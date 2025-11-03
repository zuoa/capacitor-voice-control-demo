package com.example.speechrec.baiduasr;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import com.getcapacitor.JSObject;
import com.baidu.speech.EventListener;
import com.baidu.speech.EventManager;
import com.baidu.speech.EventManagerFactory;
import com.baidu.speech.asr.SpeechConstant;
import com.example.speechrec.BuildConfig;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class BaiduAsrManager {
    private final Context appContext;
    private final EventEmitter eventEmitter;
    private boolean initialized = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isListening = false;
    private EventManager asr;
    private EventListener asrListener;
    private String languagePreference = "zh";
    private boolean vadEnabled = true;
    private boolean continuousMode = false; // Whether to auto-restart after each sentence
    private final KeywordMatcher keywordMatcher; // 关键词匹配器
    private boolean keywordFilterEnabled = false; // 是否启用关键词过滤（只返回匹配的结果）

    public interface EventEmitter {
        void emit(String event, JSObject data);
    }

    public BaiduAsrManager(Context appContext, EventEmitter eventEmitter) {
        this.appContext = appContext;
        this.eventEmitter = eventEmitter;
        this.keywordMatcher = new KeywordMatcher();
    }

    public void initialize(boolean enableVad, String language) {
        // Initialize Baidu ASR EventManager and listener
        asr = EventManagerFactory.create(appContext, "asr");
        asrListener = new EventListener() {
            @Override
            public void onEvent(String name, String params, byte[] data, int offset, int length) {
                handleAsrEvent(name, params);
            }
        };
        asr.registerListener(asrListener);
        // Save preferences for later start
        this.vadEnabled = enableVad;
        this.languagePreference = language != null ? language : "zh";
        initialized = true;
        JSObject data = new JSObject();
        data.put("message", "ready");
        eventEmitter.emit("onReady", data);
    }

    public void startListening() {
        startListening(true); // Default to continuous mode
    }

    public void startListening(boolean continuous) {
        ensureInit();
        if (isListening) return;
        
        this.continuousMode = continuous;
        
        // Always recreate EventManager for each new session to ensure clean state
        // This prevents "Broken pipe" errors from stale connections
        cleanupAsr();
        asr = EventManagerFactory.create(appContext, "asr");
        if (asrListener != null) {
            asr.registerListener(asrListener);
        }

        JSONObject params = new JSONObject();
        try {
            // Basic
            params.put(SpeechConstant.APP_ID, BuildConfig.BAIDU_APP_ID);
            params.put(SpeechConstant.APP_KEY, BuildConfig.BAIDU_API_KEY);
            params.put(SpeechConstant.SECRET, BuildConfig.BAIDU_SECRET);

            // Language / model
            // Baidu supports dev_pid for language models; fallback to 1537 (Chinese Mandarin input)
            int devPid = 1537; // Chinese mandarin, input method model
            // Simple mapping
            if ("en".equalsIgnoreCase(languagePreference)) {
                devPid = 1737; // English
            }
            // Some SDK versions don't expose DEV_PID constant; use literal key
            params.put(SpeechConstant.PID, devPid);

            // VAD and punctuation
            params.put(SpeechConstant.VAD, vadEnabled ? SpeechConstant.VAD_DNN : SpeechConstant.VAD_TOUCH);
            params.put(SpeechConstant.DISABLE_PUNCTUATION, 0);

            // Volume
            params.put(SpeechConstant.ACCEPT_AUDIO_VOLUME, true);

            // App doesn't need raw audio data back; avoid extra pipe traffic
            params.put(SpeechConstant.ACCEPT_AUDIO_DATA, false);

            // Explicitly enforce 16k sample rate to match actual capture pipeline
            params.put(SpeechConstant.SAMPLE_RATE, 16000);

            params.put(SpeechConstant.AUDIO_SOURCE, MediaRecorder.AudioSource.VOICE_RECOGNITION);

            // Endpoint timeout (ms) - time of silence before considering speech ended
            // In continuous mode, use shorter timeout for better responsiveness
            params.put(SpeechConstant.VAD_ENDPOINT_TIMEOUT, continuousMode ? 1500 : 2000);

            // Some SDK versions don't expose a global speech timeout; skip if constant is absent
        } catch (JSONException e) {
            JSObject err = new JSObject();
            err.put("message", "ASR params error: " + e.getMessage());
            eventEmitter.emit("onError", err);
        }

        asr.send(SpeechConstant.ASR_START, params.toString(), null, 0, 0);
        isListening = true;
    }

    public void stopListening() {
        if (!isListening) return;
        continuousMode = false; // Stop auto-restart
        asr.send(SpeechConstant.ASR_STOP, null, null, 0, 0);
        // Safety: if no finish event arrives, cancel to ensure mic thread stops and sockets close
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isListening) {
                    asr.send(SpeechConstant.ASR_CANCEL, "{}", null, 0, 0);
                    isListening = false;
                }
            }
        }, 2000);
    }

    public void cancel() {
        if (!isListening) return;
        continuousMode = false; // Stop auto-restart
        asr.send(SpeechConstant.ASR_CANCEL, "{}", null, 0, 0);
        isListening = false;
    }

    public void release() {
        // Ensure any running session is cancelled
        if (isListening && asr != null) {
            asr.send(SpeechConstant.ASR_CANCEL, "{}", null, 0, 0);
            isListening = false;
        }
        cleanupAsr();
        asrListener = null;
        initialized = false;
    }
    
    private void cleanupAsr() {
        if (asr != null) {
            if (asrListener != null) {
                try {
                    asr.unregisterListener(asrListener);
                } catch (Throwable ignored) {}
            }
            asr = null;
        }
    }

    private void ensureInit() {
        if (!initialized) {
            throw new IllegalStateException("BaiduAsrManager not initialized");
        }
    }

    private void handleAsrEvent(String name, String params) {
        try {
            if ("asr.partial".equals(name)) {
                String text = extractBestResult(params);
                if (text != null) {
                    // 关键词匹配
                    KeywordMatcher.MatchResult matchResult = keywordMatcher.match(text);
                    
                    JSObject d = new JSObject();
                    d.put("text", text);
                    d.put("keywordMatch", matchResult.toJSObject());
                    
                    // 如果启用了关键词过滤，只发送匹配的结果
                    if (!keywordFilterEnabled || matchResult.matched) {
                        eventEmitter.emit("onPartial", d);
                    }
                    
                    // 如果匹配到关键词，发送专门的关键词事件
                    if (matchResult.matched) {
                        eventEmitter.emit("onKeywordDetected", matchResult.toJSObject());
                    }
                }
            } else if ("asr.finish".equals(name) || "asr.final".equals(name)) {
                String text = extractBestResult(params);
                if (text != null) {
                    // 关键词匹配
                    KeywordMatcher.MatchResult matchResult = keywordMatcher.match(text);
                    
                    JSObject d = new JSObject();
                    d.put("text", text);
                    d.put("keywordMatch", matchResult.toJSObject());
                    
                    // 如果启用了关键词过滤，只发送匹配的结果
                    if (!keywordFilterEnabled || matchResult.matched) {
                        eventEmitter.emit("onFinal", d);
                    }
                    
                    // 如果匹配到关键词，发送专门的关键词事件
                    if (matchResult.matched) {
                        eventEmitter.emit("onKeywordDetected", matchResult.toJSObject());
                    }
                }
                isListening = false;
                
                // In continuous mode, automatically restart recognition for next sentence
                if (continuousMode) {
                    // Small delay to allow cleanup, then restart
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (continuousMode && !isListening) {
                                // Cleanup old instance
                                cleanupAsr();
                                // Restart new recognition session
                                startListening(true);
                            }
                        }
                    }, 300); // 300ms delay for smooth transition
                }
            } else if ("asr.finish.error".equals(name) || "asr.error".equals(name)) {
                JSObject d = new JSObject();
                d.put("message", params != null ? params : "ASR error");
                eventEmitter.emit("onError", d);
                isListening = false;
                // Cancel on error to stop any ongoing operations
                if (asr != null) {
                    asr.send(SpeechConstant.ASR_CANCEL, "{}", null, 0, 0);
                }
            }
        } catch (Exception e) {
            JSObject d = new JSObject();
            d.put("message", "ASR event parse error: " + e.getMessage());
            eventEmitter.emit("onError", d);
        }
    }

    private String extractBestResult(String json) throws JSONException {
        if (json == null || json.isEmpty()) return null;
        JSONObject obj = new JSONObject(json);
        if (obj.has("best_result")) {
            return obj.optString("best_result", null);
        }
        if (obj.has("results_recognition")) {
            JSONArray arr = obj.optJSONArray("results_recognition");
            if (arr != null && arr.length() > 0) {
                return arr.optString(0, null);
            }
        }
        if (obj.has("result")) {
            JSONArray arr = obj.optJSONArray("result");
            if (arr != null && arr.length() > 0) {
                return arr.optString(0, null);
            }
        }
        return null;
    }
    
    /**
     * 设置关键词列表
     */
    public void setKeywords(JSONArray keywords) throws JSONException {
        keywordMatcher.setKeywords(keywords);
    }
    
    /**
     * 启用/禁用关键词过滤
     * 如果启用，只有匹配到关键词的结果才会通过onPartial和onFinal事件返回
     */
    public void setKeywordFilterEnabled(boolean enabled) {
        this.keywordFilterEnabled = enabled;
    }
    
    /**
     * 设置关键词匹配置信度阈值
     */
    public void setKeywordConfidenceThreshold(float threshold) {
        keywordMatcher.setConfidenceThreshold(threshold);
    }
}


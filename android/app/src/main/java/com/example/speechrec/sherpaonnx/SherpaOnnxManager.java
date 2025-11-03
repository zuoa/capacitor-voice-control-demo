package com.example.speechrec.sherpaonnx;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import androidx.annotation.RequiresApi;
import com.getcapacitor.JSObject;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sherpa-ONNX 管理器
 * 封装 sherpa-onnx 的初始化和识别逻辑
 * 
 * 注意：此实现提供了一个框架，实际使用时需要：
 * 1. 添加 sherpa-onnx 的 native 库（.so 文件）到 jniLibs
 * 2. 添加 sherpa-onnx 的 Java API 依赖
 * 3. 下载或准备关键词识别模型文件
 */
public class SherpaOnnxManager {

    private static final String TAG = "SherpaOnnxManager";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_FACTOR = 2;
    
    // 自适应采样率列表（按优先级排序）
    private static final int[] SAMPLE_RATES = {
        44100, // CD质量
        48000, // 专业音频标准
        22050, // 中等质量
        16000, // 语音质量（默认）
        11025, // 低质量
        8000   // 电话质量
    };

    private Context context;
    private EventEmitter eventEmitter;
    
    // Sherpa-ONNX 相关
    private KeywordSpotter spotter; // sherpa-onnx 的关键词识别器
    private OnlineStream stream; // sherpa-onnx 的音频流
    
    private AudioRecord audioRecord;
    private Thread recognitionThread;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private AtomicBoolean isPaused = new AtomicBoolean(false);
    
    private String[] keywords = new String[0];
    private float threshold = 0.2f;
    private int sampleRate = SAMPLE_RATE;
    private int numThreads = 1;
    private String modelPath;

    public interface EventEmitter {
        void emit(String event, JSObject data);
    }

    public SherpaOnnxManager(Context context, EventEmitter eventEmitter) {
        this.context = context;
        this.eventEmitter = eventEmitter;
    }

    /**
     * 初始化 sherpa-onnx 引擎
     */
    public boolean initialize(String modelPath, String[] keywords, int sampleRate, int numThreads, float threshold) {
        this.modelPath = modelPath;
        this.keywords = keywords != null ? keywords : new String[0];
        this.sampleRate = sampleRate;
        this.numThreads = numThreads;
        this.threshold = threshold;

        Log.i(TAG, "Initializing Sherpa-ONNX with built-in keywords from model");

        try {
            // 1. 加载模型文件（从assets或指定路径）
            String actualModelPath = modelPath;
            if (actualModelPath == null || actualModelPath.isEmpty()) {
                // 从assets加载默认模型目录
                actualModelPath = copyModelDirectoryToCache("sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile");
                if (actualModelPath == null) {
                    throw new Exception("Failed to copy model directory from assets");
                }
            }
            
            Log.i(TAG, "Model path: " + actualModelPath);
            
            // 2. 创建 KeywordSpotterConfig
            KeywordSpotterConfig config = createKeywordSpotterConfig(actualModelPath, this.keywords, this.sampleRate, this.numThreads, this.threshold);
            
            // 3. 创建 KeywordSpotter 实例
            Log.i(TAG, "Creating KeywordSpotter instance...");
            spotter = new KeywordSpotter(config);
            
            if (spotter == null) {
                throw new Exception("KeywordSpotter creation returned null");
            }
            
            // 4. 创建音频流
            Log.i(TAG, "Creating audio stream...");
            stream = spotter.createStream();
            
            if (stream == null) {
                throw new Exception("Stream creation returned null");
            }
            
            Log.i(TAG, "Sherpa-ONNX initialized successfully with model path: " + actualModelPath);
            emitEvent("onReady", new JSObject());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Sherpa-ONNX", e);
            emitError("INIT_ERROR", "Failed to initialize: " + e.getMessage());
            
            // 清理资源
            stream = null;
            if (spotter != null) {
                try {
                    spotter.release();
                } catch (Exception ex) {
                    Log.e(TAG, "Error releasing spotter after init failure", ex);
                }
                spotter = null;
            }
            return false;
        }
    }

    /**
     * 开始识别（使用默认麦克风）
     */
    public boolean start() {
        return start(null);
    }

    /**
     * 开始识别（支持指定麦克风设备）
     * @param selectedDevice 选定的音频输入设备，null 表示使用默认设备
     */
    public boolean start(AudioDeviceInfo selectedDevice) {
        if (isRunning.get()) {
            Log.w(TAG, "Already running");
            return false;
        }

        // Keywords are loaded from model's built-in keywords.txt file

        try {
            // 使用自适应采样率查找可用的AudioRecord配置
            AudioRecordConfig audioConfig = findWorkingAudioRecord(selectedDevice);
            if (audioConfig == null) {
                Log.e(TAG, "Failed to find working AudioRecord configuration");
                emitError("AUDIO_ERROR", "Failed to find compatible audio configuration");
                return false;
            }
            
            // 如果采样率发生了变化，需要重新初始化sherpa-onnx引擎
            if (audioConfig.sampleRate != this.sampleRate) {
                Log.i(TAG, "Sample rate changed from " + this.sampleRate + "Hz to " + 
                    audioConfig.sampleRate + "Hz, reinitializing sherpa-onnx...");
                
                // 保存当前状态
                boolean wasInitialized = (spotter != null && stream != null);
                
                // 更新采样率
                int oldSampleRate = this.sampleRate;
                this.sampleRate = audioConfig.sampleRate;
                
                // 重新初始化sherpa-onnx（如果已初始化）
                if (wasInitialized) {
                    // 释放旧的stream和spotter
                    stream = null;
                    if (spotter != null) {
                        try {
                            spotter.release();
                        } catch (Exception e) {
                            Log.e(TAG, "Error releasing old spotter", e);
                        }
                        spotter = null;
                    }
                    
                    // 重新初始化
                    String actualModelPath = modelPath;
                    if (actualModelPath == null || actualModelPath.isEmpty()) {
                        actualModelPath = copyModelDirectoryToCache("sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile");
                    }
                    
                    if (actualModelPath != null) {
                        try {
                            KeywordSpotterConfig config = createKeywordSpotterConfig(
                                actualModelPath, this.keywords, this.sampleRate, this.numThreads, this.threshold);
                            spotter = new KeywordSpotter(config);
                            stream = spotter != null ? spotter.createStream() : null;
                            
                            if (spotter != null && stream != null) {
                                Log.i(TAG, "Sherpa-ONNX reinitialized with new sample rate: " + this.sampleRate + "Hz");
                            } else {
                                Log.e(TAG, "Failed to reinitialize sherpa-onnx with new sample rate");
                                this.sampleRate = oldSampleRate; // 恢复旧采样率
                                // 释放已创建的AudioRecord
                                audioConfig.audioRecord.release();
                                emitError("INIT_ERROR", "Failed to reinitialize with new sample rate");
                                return false;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to reinitialize sherpa-onnx", e);
                            this.sampleRate = oldSampleRate; // 恢复旧采样率
                            // 释放已创建的AudioRecord
                            audioConfig.audioRecord.release();
                            emitError("INIT_ERROR", "Failed to reinitialize: " + e.getMessage());
                            return false;
                        }
                    } else {
                        Log.e(TAG, "Cannot reinitialize: model path not available");
                        this.sampleRate = oldSampleRate; // 恢复旧采样率
                        // 释放已创建的AudioRecord
                        audioConfig.audioRecord.release();
                        emitError("INIT_ERROR", "Model path not available for reinitialization");
                        return false;
                    }
                }
            }
            
            // 使用找到的配置创建AudioRecord
            audioRecord = audioConfig.audioRecord;
            Log.i(TAG, "Using AudioRecord configuration: " + audioConfig.sampleRate + "Hz, " +
                "channel=" + audioConfig.channelConfig + ", format=" + audioConfig.audioFormat +
                ", source=" + audioConfig.audioSource);

            // 设置首选设备（如果指定）
            if (selectedDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Log.i(TAG, "Setting preferred device: " + selectedDevice.getProductName() + 
                    " (type=" + selectedDevice.getType() + ", id=" + selectedDevice.getId() + 
                    ", isSource=" + selectedDevice.isSource() + ")");
                
                // 验证设备是否为音频输入源
                if (!selectedDevice.isSource()) {
                    Log.e(TAG, "ERROR: Selected device is not an audio input source!");
                    emitError("DEVICE_ERROR", "Selected device is not an audio input source");
                    audioRecord.release();
                    audioRecord = null;
                    return false;
                }
                
                boolean success = audioRecord.setPreferredDevice(selectedDevice);
                Log.i(TAG, "setPreferredDevice() returned: " + success);
                
                if (!success) {
                    Log.w(TAG, "⚠️ setPreferredDevice() returned false - will use system default routing");
                }
            } else if (selectedDevice == null) {
                Log.i(TAG, "No device specified, using system default microphone");
            } else {
                Log.w(TAG, "API level < 23, cannot set preferred device");
            }

            isRunning.set(true);
            isPaused.set(false);
            
            Log.i(TAG, "Starting AudioRecord recording...");
            audioRecord.startRecording();
            
            // 短暂延迟确保录音启动
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                // Ignore
            }

            // 验证实际使用的设备（Android M+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioDeviceInfo routedDevice = audioRecord.getRoutedDevice();
                if (routedDevice != null) {
                    Log.i(TAG, "✓ Actually recording from: " + routedDevice.getProductName() + 
                        " (type=" + routedDevice.getType() + ", id=" + routedDevice.getId() + ")");
                    
                    // 检查是否是我们想要的设备
                    if (selectedDevice != null) {
                        if (routedDevice.getId() == selectedDevice.getId()) {
                            Log.i(TAG, "✓✓ Device routing CONFIRMED - using selected device!");
                        } else {
                            Log.w(TAG, "⚠️ Device routing MISMATCH - wanted " + selectedDevice.getProductName() + 
                                " but got " + routedDevice.getProductName());
                        }
                    }
                } else {
                    Log.w(TAG, "⚠️ Cannot determine routed device - getRoutedDevice() returned null");
                }
                
                // 检查录音状态
                int recordingState = audioRecord.getRecordingState();
                Log.i(TAG, "AudioRecord recording state: " + 
                    (recordingState == AudioRecord.RECORDSTATE_RECORDING ? "RECORDING" : "NOT_RECORDING"));
            }

            recognitionThread = new Thread(this::recognitionLoop);
            recognitionThread.start();

            Log.i(TAG, "Recognition started");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recognition", e);
            emitError("START_ERROR", "Failed to start: " + e.getMessage());
            isRunning.set(false);
            return false;
        }
    }

    /**
     * 停止识别
     */
    public void stop() {
        if (!isRunning.get()) {
            return;
        }

        isRunning.set(false);
        
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }
            audioRecord = null;
        }

        if (recognitionThread != null) {
            try {
                recognitionThread.join(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for thread", e);
            }
            recognitionThread = null;
        }

        Log.i(TAG, "Recognition stopped");
    }

    /**
     * 暂停识别
     */
    public void pause() {
        isPaused.set(true);
        Log.i(TAG, "Recognition paused");
    }

    /**
     * 恢复识别
     */
    public void resume() {
        isPaused.set(false);
        Log.i(TAG, "Recognition resumed");
    }

    /**
     * 更新关键词列表
     */
    public boolean updateKeywords(String[] newKeywords) {
        this.keywords = newKeywords != null ? newKeywords : new String[0];
        Log.i(TAG, "Keywords updated: " + Arrays.toString(this.keywords));
        
        // 更新 spotter 的关键词列表需要重新初始化
        if (spotter != null && this.keywords.length > 0) {
            try {
                String modelPathToUse = this.modelPath != null && !this.modelPath.isEmpty() 
                    ? this.modelPath 
                    : copyModelDirectoryToCache("sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile");
                KeywordSpotterConfig config = createKeywordSpotterConfig(modelPathToUse, this.keywords, this.sampleRate, this.numThreads, this.threshold);
                
                // 释放旧的spotter和stream
                stream = null;
                spotter = null;
                // 创建新的spotter和stream
                spotter = new KeywordSpotter(config);
                stream = spotter.createStream();
                Log.i(TAG, "Keywords updated successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to update keywords", e);
                return false;
            }
        }
        
        return true;
    }

    /**
     * 设置检测阈值
     */
    public void setThreshold(float threshold) {
        this.threshold = threshold;
        // 更新阈值需要重新初始化spotter（如果已初始化）
        if (spotter != null && this.keywords.length > 0) {
            try {
                String modelPathToUse = this.modelPath != null && !this.modelPath.isEmpty() 
                    ? this.modelPath 
                    : copyModelDirectoryToCache("sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile");
                KeywordSpotterConfig config = createKeywordSpotterConfig(modelPathToUse, this.keywords, this.sampleRate, this.numThreads, this.threshold);
                
                // 释放旧的spotter和stream
                stream = null;
                spotter = null;
                // 创建新的spotter和stream
                spotter = new KeywordSpotter(config);
                stream = spotter.createStream();
                Log.i(TAG, "Threshold updated successfully to: " + threshold);
            } catch (Exception e) {
                Log.e(TAG, "Failed to update threshold", e);
            }
        }
    }

    /**
     * 获取状态
     */
    public JSObject getStatus() {
        JSObject status = new JSObject();
        status.put("isRunning", isRunning.get());
        status.put("isPaused", isPaused.get());
        status.put("keywordsCount", keywords.length);
        status.put("threshold", threshold);
        status.put("sampleRate", sampleRate);
        return status;
    }

    /**
     * 获取支持的关键词列表
     * 从keywords.txt文件中读取
     */
    public JSObject getKeywords() {
        JSObject result = new JSObject();
        com.getcapacitor.JSArray keywordsArray = new com.getcapacitor.JSArray();
        
        try {
            // 获取模型目录路径
            String actualModelPath = modelPath;
            if (actualModelPath == null || actualModelPath.isEmpty()) {
                actualModelPath = copyModelDirectoryToCache("sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile");
            }
            
            if (actualModelPath != null) {
                File modelDirFile = new File(actualModelPath);
                File keywordsFile = new File(modelDirFile, "keywords.txt");
                
                if (keywordsFile.exists()) {
                    // 读取keywords.txt文件
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                            new java.io.FileInputStream(keywordsFile), "UTF-8"));
                    
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) {
                            continue;
                        }
                        
                        // 提取@后面的中文关键词
                        // 格式: "n ǐ h ǎo j ūn g ē @你好军哥"
                        int atIndex = line.lastIndexOf('@');
                        if (atIndex >= 0 && atIndex < line.length() - 1) {
                            String keyword = line.substring(atIndex + 1).trim();
                            if (!keyword.isEmpty()) {
                                keywordsArray.put(keyword);
                            }
                        } else {
                            // 如果没有@符号，可能是纯文本关键词（空格分隔的字符）
                            // 尝试提取整个行作为关键词
                            String keyword = line.replaceAll("\\s+", "");
                            if (!keyword.isEmpty()) {
                                keywordsArray.put(keyword);
                            }
                        }
                    }
                    reader.close();
                    
                    Log.i(TAG, "Loaded " + keywordsArray.length() + " keywords from file");
                } else {
                    Log.w(TAG, "Keywords file not found: " + keywordsFile.getAbsolutePath());
                    // 如果文件不存在，尝试从assets读取
                    try {
                        java.io.InputStream is = context.getAssets().open(
                            "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile/keywords.txt");
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(is, "UTF-8"));
                        
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty()) {
                                continue;
                            }
                            
                            int atIndex = line.lastIndexOf('@');
                            if (atIndex >= 0 && atIndex < line.length() - 1) {
                                String keyword = line.substring(atIndex + 1).trim();
                                if (!keyword.isEmpty()) {
                                    keywordsArray.put(keyword);
                                }
                            }
                        }
                        reader.close();
                        is.close();
                        
                        Log.i(TAG, "Loaded " + keywordsArray.length() + " keywords from assets");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to read keywords from assets", e);
                    }
                }
            } else {
                Log.w(TAG, "Model path not available, cannot read keywords");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get keywords", e);
        }
        
        result.put("keywords", keywordsArray);
        return result;
    }

    /**
     * 释放资源
     */
    public void release() {
        stop();
        // 释放 sherpa-onnx 资源
        try {
            stream = null;
            if (spotter != null) {
                spotter.release();
                spotter = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing spotter", e);
        }
        Log.i(TAG, "Released");
    }

    /**
     * 识别循环
     */
    private void recognitionLoop() {
        short[] buffer = new short[4096]; // 16-bit samples
        int samplesRead;

        Log.i(TAG, "Recognition loop started");

        while (isRunning.get()) {
            if (isPaused.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }

            if (audioRecord == null) {
                break;
            }

            try {
                samplesRead = audioRecord.read(buffer, 0, buffer.length);
                
                if (samplesRead == AudioRecord.ERROR_INVALID_OPERATION || 
                    samplesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Error reading audio");
                    break;
                }

                if (samplesRead > 0) {
                    // 调用 sherpa-onnx 进行处理
                    if (spotter != null && stream != null) {
                        // 转换为 float32（sherpa-onnx 需要 float32）
                        float[] floatBuffer = new float[samplesRead];
                        for (int i = 0; i < samplesRead; i++) {
                            floatBuffer[i] = buffer[i] / 32768.0f;
                        }
                        
                        // 处理音频数据 - 使用stream的acceptWaveform
                        stream.acceptWaveform(floatBuffer, sampleRate);
                        
                        // 检查是否检测到关键词
                        while (spotter.isReady(stream)) {
                            spotter.decode(stream);
                        }
                        
                        // 获取检测结果
                        KeywordSpotterResult result = spotter.getResult(stream);
                        if (result != null) {
                            String text = result.getKeyword();
                            if (text != null && !text.isEmpty()) {
                                // 触发关键词检测事件
                                JSObject eventData = new JSObject();
                                eventData.put("keyword", text);
                                eventData.put("confidence", 1.0f);
                                eventData.put("timestamp", System.currentTimeMillis());
                                emitEvent("onKeywordDetected", eventData);
                                
                                Log.d(TAG, "Keyword detected: " + text);
                            }
                        }
                    } else {
                        // spotter未初始化，使用模拟处理
                        processAudioMock(buffer, samplesRead);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in recognition loop", e);
                break;
            }
        }

        Log.i(TAG, "Recognition loop ended");
    }

    /**
     * 模拟音频处理（仅用于测试，实际使用时需要移除）
     */
    private void processAudioMock(short[] buffer, int samplesRead) {
        // 这是一个占位实现，实际使用时需要替换为真实的 sherpa-onnx 调用
        // 当前不做任何处理，只是保持循环运行
    }

    /**
     * 从 assets 复制文件到缓存目录
     */
    private String copyAssetToCache(String filename) {
        try {
            File cacheDir = context.getCacheDir();
            File outputFile = new File(cacheDir, filename);
            
            if (outputFile.exists()) {
                return outputFile.getAbsolutePath();
            }

            InputStream is = context.getAssets().open(filename);
            FileOutputStream fos = new FileOutputStream(outputFile);
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            
            fos.close();
            is.close();
            
            Log.i(TAG, "Copied asset to cache: " + outputFile.getAbsolutePath());
            return outputFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy asset: " + filename, e);
            return null;
        }
    }

    /**
     * 从 assets 复制单个文件到目标目录
     * @param assetDir assets 中的目录路径
     * @param filename 要复制的文件名
     * @param targetDir 目标目录
     * @return 成功返回 true，失败返回 false
     */
    private boolean copySingleFileFromAssets(String assetDir, String filename, File targetDir) {
        try {
            String assetPath = assetDir + "/" + filename;
            File outputFile = new File(targetDir, filename);
            
            InputStream is = context.getAssets().open(assetPath);
            FileOutputStream fos = new FileOutputStream(outputFile);
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            
            fos.close();
            is.close();
            
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to copy file from assets: " + filename, e);
            return false;
        }
    }

    /**
     * 从 assets 复制整个模型目录到缓存目录
     * @param assetDir 模型目录在assets中的路径
     * @return 缓存目录中的模型目录路径，失败返回null
     */
    private String copyModelDirectoryToCache(String assetDir) {
        try {
            File cacheDir = context.getCacheDir();
            File modelDir = new File(cacheDir, assetDir);
            
            // 需要始终更新的可编辑配置文件
            String[] editableFiles = {"keywords.txt", "keywords_raw.txt"};
            
            // 如果目录已存在，检查关键文件是否存在
            if (modelDir.exists()) {
                File encoderFile = new File(modelDir, "encoder-epoch-12-avg-2-chunk-16-left-64.onnx");
                File decoderFile = new File(modelDir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx");
                File joinerFile = new File(modelDir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx");
                File tokensFile = new File(modelDir, "tokens.txt");
                
                if (encoderFile.exists() && decoderFile.exists() && joinerFile.exists() && tokensFile.exists()) {
                    Log.i(TAG, "Model directory already exists: " + modelDir.getAbsolutePath());
                    
                    // 即使目录已存在，也要更新可编辑的配置文件（如 keywords.txt 和 keywords_raw.txt）
                    // 这样可以确保用户修改了 assets 中的文件后，更改会被同步到缓存目录
                    for (String filename : editableFiles) {
                        try {
                            if (copySingleFileFromAssets(assetDir, filename, modelDir)) {
                                Log.d(TAG, "Updated editable file: " + filename);
                            }
                        } catch (Exception e) {
                            // 如果文件不存在于 assets 中，忽略错误（某些文件可能不存在）
                            Log.d(TAG, "File not found in assets (may not exist): " + filename);
                        }
                    }
                    
                    return modelDir.getAbsolutePath();
                } else {
                    // 文件不完整，删除目录重新复制
                    deleteDirectory(modelDir);
                }
            }
            
            // 创建目标目录
            if (!modelDir.mkdirs()) {
                Log.e(TAG, "Failed to create model directory: " + modelDir.getAbsolutePath());
                return null;
            }
            
            // 列出assets目录中的所有文件
            String[] files = context.getAssets().list(assetDir);
            if (files == null || files.length == 0) {
                Log.e(TAG, "Asset directory is empty or not found: " + assetDir);
                return null;
            }
            
            Log.i(TAG, "Copying model files from assets...");
            int copiedCount = 0;
            
            // 复制所有文件
            for (String filename : files) {
                if (copySingleFileFromAssets(assetDir, filename, modelDir)) {
                    copiedCount++;
                    Log.d(TAG, "Copied: " + filename);
                } else {
                    Log.w(TAG, "Failed to copy file: " + filename);
                    // 继续复制其他文件
                }
            }
            
            Log.i(TAG, "Copied " + copiedCount + " files to: " + modelDir.getAbsolutePath());
            return modelDir.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy model directory: " + assetDir, e);
            return null;
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }

    /**
     * 创建 KeywordSpotterConfig
     * 根据sherpa-onnx的API结构正确配置，使用Builder模式
     */
    private KeywordSpotterConfig createKeywordSpotterConfig(String modelDir, String[] keywords, int sampleRate, int numThreads, float threshold) throws Exception {
        // 设置模型文件路径
        File modelDirFile = new File(modelDir);
        String encoderPath = new File(modelDirFile, "encoder-epoch-12-avg-2-chunk-16-left-64.onnx").getAbsolutePath();
        String decoderPath = new File(modelDirFile, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx").getAbsolutePath();
        String joinerPath = new File(modelDirFile, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx").getAbsolutePath();
        String tokensPath = new File(modelDirFile, "tokens.txt").getAbsolutePath();
        
        // 使用模型自带的关键词文件
        String keywordsFilePath = new File(modelDirFile, "keywords.txt").getAbsolutePath();
        
        // 验证模型文件是否存在
        if (!new File(encoderPath).exists()) {
            throw new Exception("Encoder model file not found: " + encoderPath);
        }
        if (!new File(decoderPath).exists()) {
            throw new Exception("Decoder model file not found: " + decoderPath);
        }
        if (!new File(joinerPath).exists()) {
            throw new Exception("Joiner model file not found: " + joinerPath);
        }
        if (!new File(tokensPath).exists()) {
            throw new Exception("Tokens file not found: " + tokensPath);
        }
        if (!new File(keywordsFilePath).exists()) {
            throw new Exception("Keywords file not found: " + keywordsFilePath);
        }
        
        Log.i(TAG, "Creating KeywordSpotterConfig with:");
        Log.i(TAG, "  encoder: " + encoderPath);
        Log.i(TAG, "  decoder: " + decoderPath);
        Log.i(TAG, "  joiner: " + joinerPath);
        Log.i(TAG, "  tokens: " + tokensPath);
        Log.i(TAG, "  keywords file: " + keywordsFilePath);
        Log.i(TAG, "  threshold: " + threshold);
        
        // 使用 Builder 模式创建配置
        OnlineTransducerModelConfig transducerConfig = OnlineTransducerModelConfig.builder()
            .setEncoder(encoderPath)
            .setDecoder(decoderPath)
            .setJoiner(joinerPath)
            .build();
        
        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
            .setTransducer(transducerConfig)
            .setTokens(tokensPath)
            .setNumThreads(numThreads)
            .setDebug(false)
            .build();
        
        KeywordSpotterConfig config = KeywordSpotterConfig.builder()
            .setOnlineModelConfig(modelConfig)
            .setKeywordsFile(keywordsFilePath)
            .setMaxActivePaths(4)
            .setKeywordsThreshold(threshold)
            .build();
        
        return config;
    }
    
    /**
     * 创建关键词文件
     * sherpa-onnx 需要从文件读取关键词列表
     * 对于中文关键词，需要将每个字符用空格分隔
     */
    private File createKeywordsFile(String[] keywords) {
        try {
            if (keywords == null || keywords.length == 0) {
                Log.e(TAG, "Cannot create keywords file: keywords array is null or empty");
                return null;
            }
            
            File cacheDir = context.getCacheDir();
            File keywordsFile = new File(cacheDir, "keywords.txt");
            
            FileOutputStream fos = new FileOutputStream(keywordsFile);
            int validKeywordCount = 0;
            for (String keyword : keywords) {
                if (keyword != null && !keyword.trim().isEmpty()) {
                    // 将中文短语转换为空格分隔的字符
                    // 例如: "打开灯" -> "打 开 灯"
                    String processedKeyword = convertToSpaceSeparated(keyword.trim());
                    fos.write((processedKeyword + "\n").getBytes("UTF-8"));
                    Log.d(TAG, "Processed keyword: " + keyword.trim() + " -> " + processedKeyword);
                    validKeywordCount++;
                }
            }
            fos.close();
            
            if (validKeywordCount == 0) {
                Log.e(TAG, "No valid keywords found");
                keywordsFile.delete();
                return null;
            }
            
            Log.d(TAG, "Keywords file created: " + keywordsFile.getAbsolutePath() + " with " + validKeywordCount + " keywords");
            return keywordsFile;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create keywords file", e);
            return null;
        }
    }

    /**
     * 将字符串转换为空格分隔的字符
     * 这对于中文关键词识别是必需的
     */
    private String convertToSpaceSeparated(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 跳过已有的空格
            if (c == ' ') {
                continue;
            }
            
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(c);
        }
        
        return result.toString();
    }

    private void emitEvent(String event, JSObject data) {
        if (eventEmitter != null) {
            eventEmitter.emit(event, data);
        }
    }

    private void emitError(String code, String message) {
        JSObject error = new JSObject();
        error.put("code", code);
        error.put("message", message);
        emitEvent("onError", error);
    }
    
    /**
     * AudioRecord配置信息
     */
    private static class AudioRecordConfig {
        AudioRecord audioRecord;
        int sampleRate;
        int channelConfig;
        int audioFormat;
        int audioSource;
        
        AudioRecordConfig(AudioRecord audioRecord, int sampleRate, int channelConfig, 
                         int audioFormat, int audioSource) {
            this.audioRecord = audioRecord;
            this.sampleRate = sampleRate;
            this.channelConfig = channelConfig;
            this.audioFormat = audioFormat;
            this.audioSource = audioSource;
        }
    }
    
    /**
     * 查找可用的AudioRecord配置（自适应采样率）
     * 参考用户提供的代码，遍历不同的采样率、通道配置和音频格式
     * @param selectedDevice 选定的音频输入设备，null 表示使用默认设备
     * @return 找到的AudioRecord配置，失败返回null
     */
    private AudioRecordConfig findWorkingAudioRecord(AudioDeviceInfo selectedDevice) {
        // 优先尝试的音频源（按优先级排序）
        int[] audioSources = {
            MediaRecorder.AudioSource.VOICE_RECOGNITION,  // 专为语音识别优化
            MediaRecorder.AudioSource.MIC,                 // 标准麦克风
            MediaRecorder.AudioSource.UNPROCESSED,         // 无处理原始音频（如果支持）
            MediaRecorder.AudioSource.DEFAULT              // 系统默认
        };
        
        // 优先尝试用户指定的采样率，如果没有找到再尝试其他采样率
        int[] candidateSampleRates = new int[SAMPLE_RATES.length + 1];
        candidateSampleRates[0] = this.sampleRate; // 将用户指定的采样率放在首位
        System.arraycopy(SAMPLE_RATES, 0, candidateSampleRates, 1, SAMPLE_RATES.length);
        
        // 遍历所有配置组合
        for (int sampleRate : candidateSampleRates) {
            for (short channelConfig : new short[] {
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.CHANNEL_IN_STEREO
            }) {
                for (short audioFormat : new short[] {
                    AudioFormat.ENCODING_PCM_16BIT,
                    AudioFormat.ENCODING_PCM_8BIT
                }) {
                    for (int audioSource : audioSources) {
                        try {
                            int bufferSize = AudioRecord.getMinBufferSize(
                                sampleRate, channelConfig, audioFormat
                            );
                            
                            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || 
                                bufferSize == AudioRecord.ERROR) {
                                continue; // 此配置不可用，尝试下一个
                            }
                            
                            bufferSize *= BUFFER_SIZE_FACTOR;
                            
                            AudioRecord recorder = new AudioRecord(
                                audioSource,
                                sampleRate,
                                channelConfig,
                                audioFormat,
                                bufferSize
                            );
                            
                            if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                                // 验证设备兼容性（如果指定了设备）
                                if (selectedDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    if (!selectedDevice.isSource()) {
                                        recorder.release();
                                        continue; // 设备不是输入源，跳过
                                    }
                                    // 注意：不在这里设置设备，让start方法在创建后统一设置
                                }
                                
                                Log.i(TAG, "✓ Found working AudioRecord configuration: " + 
                                    sampleRate + "Hz, channel=" + channelConfig + 
                                    ", format=" + audioFormat + ", source=" + audioSource);
                                
                                return new AudioRecordConfig(recorder, sampleRate, 
                                    channelConfig, audioFormat, audioSource);
                            } else {
                                recorder.release();
                            }
                        } catch (Exception e) {
                            // 继续尝试下一个配置
                            Log.d(TAG, "Exception trying configuration: " + sampleRate + 
                                "Hz, channel=" + channelConfig + ", format=" + audioFormat + 
                                ", source=" + audioSource + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
        
        Log.e(TAG, "✗ Failed to find any working AudioRecord configuration");
        return null;
    }
}


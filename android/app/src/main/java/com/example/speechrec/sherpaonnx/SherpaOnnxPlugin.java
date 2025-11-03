package com.example.speechrec.sherpaonnx;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.RequiresApi;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.PermissionState;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * Sherpa-ONNX 指令识别插件
 * 基于 sherpa-onnx 的离线关键词识别引擎
 */
@CapacitorPlugin(
    name = "SherpaOnnx",
    permissions = {
        @Permission(strings = {android.Manifest.permission.RECORD_AUDIO}, alias = SherpaOnnxPlugin.PERM_AUDIO)
    }
)
public class SherpaOnnxPlugin extends Plugin {

    public static final String PERM_AUDIO = "microphone";

    private SherpaOnnxManager manager;
    private Handler mainHandler;
    private AudioManager audioManager;
    private AudioDeviceInfo selectedInputDevice;
    private AudioRecord routingAudioRecord;

    private boolean isAudioGranted() {
        return getPermissionState(PERM_AUDIO) == PermissionState.GRANTED;
    }

    @Override
    public void load() {
        super.load();
        manager = new SherpaOnnxManager(getContext(), this::emitEvent);
        mainHandler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        android.util.Log.i("SherpaOnnxPlugin", "Plugin loaded");
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        if (manager != null) {
            manager.pause();
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (manager != null) {
            manager.resume();
        }
    }

    @Override
    protected void handleOnDestroy() {
        if (manager != null) {
            manager.release();
        }
        super.handleOnDestroy();
    }

    private void emitEvent(String event, JSObject data) {
        notifyListeners(event, data, true);
    }

    /**
     * 初始化 sherpa-onnx 引擎
     * @param call 包含配置参数：
     *   - modelPath: 模型文件路径（可选，默认从assets加载）
     *   - keywords: 关键词列表（字符串数组）
     *   - sampleRate: 采样率（默认16000）
     *   - numThreads: 线程数（默认1）
     */
    @PluginMethod
    public void init(PluginCall call) {
        if (!isAudioGranted()) {
            requestPermissionForAlias(PERM_AUDIO, call, "permCallback");
            return;
        }

        try {
            String modelPath = call.getString("modelPath", null);
            String[] keywords = extractStringArray(call, "keywords");
            int sampleRate = call.getInt("sampleRate", 16000);
            int numThreads = call.getInt("numThreads", 1);
            Double thresholdObj = call.getDouble("threshold", 0.2);
            float threshold = thresholdObj != null ? thresholdObj.floatValue() : 0.2f;

            boolean success = manager.initialize(modelPath, keywords, sampleRate, numThreads, threshold);
            
            JSObject ret = new JSObject();
            ret.put("ok", success);
            if (success) {
                ret.put("message", "Sherpa-ONNX initialized successfully");
            } else {
                ret.put("message", "Failed to initialize Sherpa-ONNX");
            }
            call.resolve(ret);
        } catch (Exception e) {
            android.util.Log.e("SherpaOnnxPlugin", "Init error", e);
            call.reject("Init failed: " + e.getMessage());
        }
    }

    @PermissionCallback
    private void permCallback(PluginCall call) {
        if (isAudioGranted()) {
            // 重新调用 init
            init(call);
        } else {
            call.reject("Microphone permission denied");
        }
    }

    /**
     * 开始识别
     */
    @PluginMethod
    public void start(PluginCall call) {
        if (!isAudioGranted()) {
            call.reject("Microphone permission not granted");
            return;
        }

        try {
            // 先释放之前的路由 AudioRecord（如果有）
            releaseRoutingAudioRecord();
            
            // 在录音开始前设置首选设备（仅用于 Android 12+ 的通信设备设置）
            if (selectedInputDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.util.Log.i("SherpaOnnxPlugin", "Start: Selected device is " + 
                    selectedInputDevice.getProductName() + " (type=" + selectedInputDevice.getType() + 
                    ", id=" + selectedInputDevice.getId() + ")");
                
                // 先验证设备是否仍然可用
                if (!validateSelectedDevice()) {
                    android.util.Log.w("SherpaOnnxPlugin", "Selected device validation failed, using system default");
                } else {
                    // Android 12+: 尝试设置通信设备（对某些设备类型可能有帮助）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            boolean commDeviceSet = applySelectedDevice();
                            android.util.Log.i("SherpaOnnxPlugin", 
                                "setCommunicationDevice attempted: " + commDeviceSet);
                        } catch (Exception e) {
                            android.util.Log.w("SherpaOnnxPlugin", 
                                "setCommunicationDevice failed (not critical): " + e.getMessage());
                        }
                    }
                }
            } else {
                // 如果没有选择设备或版本过低，使用系统默认
                if (selectedInputDevice == null) {
                    android.util.Log.i("SherpaOnnxPlugin", "Start: No device selected, using system default");
                } else {
                    android.util.Log.i("SherpaOnnxPlugin", "Start: API < 23, cannot set preferred device");
                }
            }
            
            // 传递选定的设备给 manager，由 manager 直接设置 AudioRecord 的首选设备
            boolean success = manager.start(selectedInputDevice);
            JSObject ret = new JSObject();
            ret.put("ok", success);
            call.resolve(ret);
        } catch (Exception e) {
            android.util.Log.e("SherpaOnnxPlugin", "Start error", e);
            call.reject("Start failed: " + e.getMessage());
        }
    }

    /**
     * 停止识别
     */
    @PluginMethod
    public void stop(PluginCall call) {
        try {
            manager.stop();
            releaseRoutingAudioRecord(); // 释放路由 AudioRecord
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (Exception e) {
            android.util.Log.e("SherpaOnnxPlugin", "Stop error", e);
            call.reject("Stop failed: " + e.getMessage());
        }
    }

    /**
     * 更新关键词列表
     * @param call 包含 keywords 数组
     */
    @PluginMethod
    public void updateKeywords(PluginCall call) {
        try {
            String[] keywords = extractStringArray(call, "keywords");
            
            boolean success = manager.updateKeywords(keywords);
            JSObject ret = new JSObject();
            ret.put("ok", success);
            call.resolve(ret);
        } catch (Exception e) {
            android.util.Log.e("SherpaOnnxPlugin", "Update keywords error", e);
            call.reject("Update keywords failed: " + e.getMessage());
        }
    }

    /**
     * 设置检测阈值
     * @param call 包含 threshold (0.0-1.0)
     */
    @PluginMethod
    public void setThreshold(PluginCall call) {
        try {
            Double thresholdObj = call.getDouble("threshold", 0.2);
            float threshold = thresholdObj != null ? thresholdObj.floatValue() : 0.2f;
            manager.setThreshold(threshold);
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (Exception e) {
            android.util.Log.e("SherpaOnnxPlugin", "Set threshold error", e);
            call.reject("Set threshold failed: " + e.getMessage());
        }
    }

    /**
     * 获取识别状态
     */
    @PluginMethod
    public void getStatus(PluginCall call) {
        try {
            JSObject status = manager.getStatus();
            call.resolve(status);
        } catch (Exception e) {
            android.util.Log.e("SherpaOnnxPlugin", "Get status error", e);
            call.reject("Get status failed: " + e.getMessage());
        }
    }

    /**
     * 获取支持的关键词列表
     */
    @PluginMethod
    public void getKeywords(PluginCall call) {
        try {
            JSObject result = manager.getKeywords();
            call.resolve(result);
        } catch (Exception e) {
            android.util.Log.e("SherpaOnnxPlugin", "Get keywords error", e);
            call.reject("Get keywords failed: " + e.getMessage());
        }
    }

    /**
     * 从 PluginCall 中提取字符串数组
     */
    private String[] extractStringArray(PluginCall call, String key) {
        try {
            JSArray jsArray = call.getArray(key);
            if (jsArray == null) {
                return new String[0];
            }
            
            // 将 JSArray 转换为 JSONArray，然后提取字符串
            JSONArray jsonArray = new JSONArray(jsArray.toString());
            String[] result = new String[jsonArray.length()];
            for (int i = 0; i < jsonArray.length(); i++) {
                result[i] = jsonArray.getString(i);
            }
            return result;
        } catch (JSONException e) {
            android.util.Log.e("SherpaOnnxPlugin", "Failed to extract string array for key: " + key, e);
            return new String[0];
        }
    }

    /**
     * 枚举原生音频输入设备
     */
    @PluginMethod
    public void listInputs(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            JSArray arr = new JSArray();
            if (audioManager != null) {
                AudioDeviceInfo[] inputs;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // 切换音频模式来触发重新扫描
                    int originalMode = audioManager.getMode();
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {}
                    audioManager.setMode(originalMode);

                    inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
                    
                    android.util.Log.i("SherpaOnnxPlugin", 
                        "Enumerating audio input devices: found " + inputs.length + " devices");
                    
                    boolean hasPermission = isAudioGranted();
                    android.util.Log.i("SherpaOnnxPlugin", 
                        "RECORD_AUDIO permission granted: " + hasPermission);
                } else {
                    inputs = new AudioDeviceInfo[0];
                    android.util.Log.w("SherpaOnnxPlugin", 
                        "API level < 23, cannot enumerate audio devices");
                }
                
                for (AudioDeviceInfo d : inputs) {
                    String productName = d.getProductName() != null ? 
                        d.getProductName().toString() : "Unknown";
                    int type = d.getType();
                    int id = d.getId();
                    boolean isSource = d.isSource();
                    String address = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? 
                        d.getAddress() : "N/A";
                    
                    android.util.Log.i("SherpaOnnxPlugin", 
                        String.format("Device: name='%s', type=%d, id=%d, address='%s', isSource=%s",
                            productName, type, id, address, isSource));
                    
                    boolean isUsbDevice = (type == AudioDeviceInfo.TYPE_USB_DEVICE || 
                                         type == AudioDeviceInfo.TYPE_USB_HEADSET);
                    if (isUsbDevice) {
                        android.util.Log.i("SherpaOnnxPlugin", 
                            "✓ USB device detected: " + productName + " (type=" + type + ")");
                    }
                    
                    JSObject o = new JSObject();
                    o.put("type", type);
                    o.put("label", productName);
                    // 构造稳定ID
                    String stableId = d.getType() + "_" + productName + "_" + d.hashCode();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        if (address != null && !address.isEmpty()) {
                            stableId = d.getType() + "_" + address;
                        }
                        o.put("address", address);
                    }
                    o.put("stableId", stableId);
                    o.put("id", id);
                    o.put("isSource", isSource);
                    arr.put(o);
                }
                
                android.util.Log.i("SherpaOnnxPlugin", 
                    "Device enumeration complete: " + arr.length() + " devices returned to frontend");
            } else {
                android.util.Log.e("SherpaOnnxPlugin", "AudioManager is null, cannot enumerate devices");
            }
            ret.put("inputs", arr);
            call.resolve(ret);
        } catch (Exception e) {
            android.util.Log.e("SherpaOnnxPlugin", "Failed to list inputs", e);
            call.reject("Failed to list inputs: " + e.getMessage());
        }
    }

    /**
     * 选择期望的输入设备
     */
    @PluginMethod
    public void selectInput(PluginCall call) {
        String stableId = call.getString("stableId");
        if (stableId == null || stableId.isEmpty()) {
            call.reject("stableId is required");
            return;
        }
        try {
            if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                JSObject ret = new JSObject();
                ret.put("ok", false);
                ret.put("message", "AudioManager not available or API < 23");
                call.resolve(ret);
                return;
            }
            AudioDeviceInfo[] inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            AudioDeviceInfo match = null;
            for (AudioDeviceInfo d : inputs) {
                CharSequence pn = d.getProductName();
                String sid = d.getType() + "_" + (pn != null ? pn.toString() : "") + "_" + d.hashCode();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    String addr = d.getAddress();
                    if (addr != null && !addr.isEmpty()) sid = d.getType() + "_" + addr;
                }
                if (stableId.equals(sid)) {
                    match = d; break;
                }
            }
            if (match != null) {
                this.selectedInputDevice = match;
                android.util.Log.i("SherpaOnnxPlugin", "Device selected: " + 
                    match.getProductName() + " (type=" + match.getType() + 
                    ", id=" + match.getId() + ")");
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !match.isSource()) {
                    android.util.Log.w("SherpaOnnxPlugin", "Warning: Selected device is not an audio input source");
                }
            } else {
                android.util.Log.w("SherpaOnnxPlugin", "Device not found with stableId: " + stableId);
            }
            
            JSObject ret = new JSObject();
            ret.put("ok", match != null);
            ret.put("applied", match != null);
            if (match != null) {
                ret.put("deviceName", match.getProductName().toString());
                ret.put("deviceType", match.getType());
                ret.put("deviceId", match.getId());
            }
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to select input: " + e.getMessage());
        }
    }

    /**
     * 验证并更新选中的设备引用
     */
    private boolean validateSelectedDevice() {
        if (selectedInputDevice == null || audioManager == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        
        try {
            AudioDeviceInfo[] currentDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo dev : currentDevices) {
                if (dev.getId() == selectedInputDevice.getId()) {
                    selectedInputDevice = dev;
                    android.util.Log.d("SherpaOnnxPlugin", "Device validated: " + 
                        dev.getProductName() + " (id=" + dev.getId() + ")");
                    return true;
                }
            }
            
            android.util.Log.w("SherpaOnnxPlugin", "Selected device no longer available");
            return false;
        } catch (Exception e) {
            android.util.Log.e("SherpaOnnxPlugin", "validateSelectedDevice failed", e);
            return false;
        }
    }
    
    /**
     * 创建并保持一个 AudioRecord 实例用于设备路由
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean createAndHoldRoutingAudioRecord(AudioDeviceInfo device) {
        try {
            if (!device.isSource()) {
                android.util.Log.w("SherpaOnnxPlugin", "Device is not an audio source: " + 
                    device.getProductName());
                return false;
            }
            
            // 释放之前的实例
            releaseRoutingAudioRecord();
            
            // 创建 AudioRecord 配置
            int sampleRate = 16000;
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                android.util.Log.e("SherpaOnnxPlugin", "Invalid buffer size for routing AudioRecord");
                return false;
            }
            
            bufferSize *= 2;
            
            // 创建 AudioRecord
            routingAudioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            );
            
            if (routingAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                android.util.Log.e("SherpaOnnxPlugin", "Failed to initialize routing AudioRecord");
                releaseRoutingAudioRecord();
                return false;
            }
            
            // 设置首选设备
            boolean success = routingAudioRecord.setPreferredDevice(device);
            android.util.Log.i("SherpaOnnxPlugin", 
                "setPreferredDevice(" + device.getProductName() + ") -> " + success);
            
            return success;
        } catch (Exception e) {
            android.util.Log.e("SherpaOnnxPlugin", "createAndHoldRoutingAudioRecord failed", e);
            releaseRoutingAudioRecord();
            return false;
        }
    }
    
    /**
     * 释放路由 AudioRecord
     */
    private void releaseRoutingAudioRecord() {
        if (routingAudioRecord != null) {
            try {
                if (routingAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    routingAudioRecord.stop();
                }
                routingAudioRecord.release();
                android.util.Log.d("SherpaOnnxPlugin", "Routing AudioRecord released");
            } catch (Exception e) {
                android.util.Log.e("SherpaOnnxPlugin", "Error releasing routing AudioRecord", e);
            }
            routingAudioRecord = null;
        }
    }
    
    /**
     * 应用选定的设备（Android 12+）
     */
    @RequiresApi(api = Build.VERSION_CODES.S)
    private boolean applySelectedDevice() {
        if (audioManager == null || selectedInputDevice == null) {
            android.util.Log.w("SherpaOnnxPlugin", "Cannot apply device: audioManager or device is null");
            return false;
        }
        
        try {
            boolean success = audioManager.setCommunicationDevice(selectedInputDevice);
            android.util.Log.i("SherpaOnnxPlugin", 
                "setCommunicationDevice: " + selectedInputDevice.getProductName() + 
                " (type=" + selectedInputDevice.getType() + ", id=" + selectedInputDevice.getId() + 
                ") -> " + success);
            
            if (success) {
                AudioDeviceInfo currentCommDevice = audioManager.getCommunicationDevice();
                if (currentCommDevice != null) {
                    android.util.Log.i("SherpaOnnxPlugin", 
                        "Current communication device: " + currentCommDevice.getProductName() + 
                        " (id=" + currentCommDevice.getId() + ")");
                }
            }
            
            return success;
        } catch (Exception e) {
            android.util.Log.e("SherpaOnnxPlugin", "applySelectedDevice failed", e);
            return false;
        }
    }
}


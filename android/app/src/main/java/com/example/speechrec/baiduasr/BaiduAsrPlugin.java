package com.example.speechrec.baiduasr;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.RequiresApi;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.PermissionState;

@CapacitorPlugin(
    name = "BaiduAsr",
    permissions = {
        @Permission(strings = {android.Manifest.permission.RECORD_AUDIO}, alias = BaiduAsrPlugin.PERM_AUDIO),
        // 普通权限：不需要运行时请求，但在此声明便于可见性和清单合并
        @Permission(strings = {android.Manifest.permission.MODIFY_AUDIO_SETTINGS}, alias = "audioSettings")
    }
)
public class BaiduAsrPlugin extends Plugin {

    public static final String PERM_AUDIO = "microphone";

    private BaiduAsrManager manager;
    private AudioManager audioManager;
    private AudioDeviceInfo selectedInputDevice;
    private AudioRecord routingAudioRecord; // 用于保持设备路由的 AudioRecord
    private Handler mainHandler; // 用于设备回调的 Handler
    // 设备变化监听器（Android 6.0+）
    private Object deviceChangeCallback; // 存储为 Object 以避免 API 级别问题
    private static final String ACTION_USB_PERMISSION = "com.example.speechrec.USB_PERMISSION";
    private PluginCall pendingUsbPermissionCall; // 用于存储等待权限回调的 PluginCall

    private boolean isAudioGranted() {
        return getPermissionState(PERM_AUDIO) == PermissionState.GRANTED;
    }

    @Override
    public void load() {
        super.load();
        // 确保正确获取 AudioManager - 使用 Activity Context 而不是 Application Context
        // 不要使用 Application Context，而是使用 Activity Context
        // AudioManager audioManager = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE); // 可能有问题
        audioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        manager = new BaiduAsrManager(getContext(), this::emitEvent);
        mainHandler = new Handler(Looper.getMainLooper());
        
        // 注册音频设备变化监听器（Android 6.0+）
        // 注意：由于编译器问题，暂时禁用自动设备监听功能
        // 用户可以通过手动刷新设备列表来更新
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
        //     registerDeviceChangeCallback();
        // }
        android.util.Log.i("BaiduAsrPlugin", "Plugin loaded. Auto device change detection is disabled. Use manual refresh.");
        
        // 注册 USB 权限广播接收器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            getContext().registerReceiver(usbPermissionReceiver, filter);
        }
    }
    
    /**
     * USB 权限广播接收器
     */
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                    } else {
                        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null && pendingUsbPermissionCall != null) {
                            android.util.Log.i("BaiduAsrPlugin", 
                                "USB permission granted for device: " + device.getDeviceName());
                            JSObject ret = new JSObject();
                            ret.put("ok", true);
                            ret.put("granted", true);
                            ret.put("deviceName", device.getDeviceName());
                            ret.put("vendorId", device.getVendorId());
                            ret.put("productId", device.getProductId());
                            pendingUsbPermissionCall.resolve(ret);
                            pendingUsbPermissionCall = null;
                        }
                    } else {
                        android.util.Log.w("BaiduAsrPlugin", 
                            "USB permission denied for device: " + 
                            (device != null ? device.getDeviceName() : "unknown"));
                        if (pendingUsbPermissionCall != null) {
                            JSObject ret = new JSObject();
                            ret.put("ok", false);
                            ret.put("granted", false);
                            ret.put("message", "USB permission denied by user");
                            pendingUsbPermissionCall.resolve(ret);
                            pendingUsbPermissionCall = null;
                        }
                    }
                }
            }
        }
    };
    


    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        if (manager != null) {
            manager.cancel();
        }
    }

    @Override
    protected void handleOnDestroy() {
        // 取消注册设备变化监听器
        // 注意：由于编译器问题，暂时禁用自动设备监听功能
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        //     unregisterDeviceChangeCallback();
        // }
        
        if (manager != null) {
            manager.release();
        }
        releaseRoutingAudioRecord();
        
        // 取消注册 USB 权限广播接收器
        try {
            getContext().unregisterReceiver(usbPermissionReceiver);
        } catch (Exception e) {
            // 可能已经取消注册，忽略错误
        }
        
        super.handleOnDestroy();
    }
    

    private void emitEvent(String event, JSObject data) {
        notifyListeners(event, data, true);
    }

    @PluginMethod
    public void init(PluginCall call) {
        if (!isAudioGranted()) {
            requestPermissionForAlias(PERM_AUDIO, call, "permCallback");
            return;
        }
        Boolean enableVad = call.getBoolean("enableVad", true);
        if (enableVad == null) enableVad = true;
        String language = call.getString("language", "zh");
        if (language == null) language = "zh";
        
        // 如果已选择设备，记录日志（不在这里设置，在 start() 时设置）
        if (selectedInputDevice != null) {
            android.util.Log.i("BaiduAsrPlugin", "Init: Device selected: " + 
                selectedInputDevice.getProductName() + " (type=" + selectedInputDevice.getType() + 
                "), will apply routing when recording starts");
        } else {
            android.util.Log.i("BaiduAsrPlugin", "Init: No device selected, using default");
        }
        
        manager.initialize(enableVad, language);
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    @PermissionCallback
    private void permCallback(PluginCall call) {
        if (isAudioGranted()) {
            init(call);
        } else {
            call.reject("Microphone permission denied");
        }
    }

    // Optional: expose for debugging permission state from JS
    @PluginMethod
    public void checkPermissions(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("microphone", getPermissionState(PERM_AUDIO).toString());
        call.resolve(ret);
    }

    @PluginMethod
    public void start(PluginCall call) {
        // 每次开始录音前再次确保路由正确
        // 先释放之前的路由 AudioRecord（如果有）
        releaseRoutingAudioRecord();
        
        // 在录音开始前设置首选设备，可能会影响百度 SDK 内部创建的 AudioRecord
        if (selectedInputDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int deviceType = selectedInputDevice.getType();
            android.util.Log.i("BaiduAsrPlugin", "Start: Selected device is " + 
                selectedInputDevice.getProductName() + " (type=" + deviceType + 
                ", id=" + selectedInputDevice.getId() + ")");
            
            // 先验证设备是否仍然可用
            if (!validateSelectedDevice()) {
                android.util.Log.w("BaiduAsrPlugin", "Selected device validation failed, using system default");
            } else {
                // 对于 USB 设备，使用特殊处理（所有 Android 版本）
                boolean isUsbDevice = (deviceType == AudioDeviceInfo.TYPE_USB_DEVICE || 
                                      deviceType == AudioDeviceInfo.TYPE_USB_HEADSET);
                
                if (isUsbDevice) {
                    android.util.Log.i("BaiduAsrPlugin", "Re-applying USB mic routing before recording start");
                    
                    // Android 12+: 双重保障机制
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // 1. 尝试设置通信设备（可能失败，但不影响后续流程）
                        boolean commDeviceSet = applySelectedDevice();
                        android.util.Log.i("BaiduAsrPlugin", 
                            "setCommunicationDevice attempted: " + commDeviceSet);
                    }
                    
                    // 2. 创建并启动路由 AudioRecord（占用设备）
                    boolean audioRecordCreated = createAndHoldRoutingAudioRecord(selectedInputDevice);
                    if (audioRecordCreated) {
                        android.util.Log.i("BaiduAsrPlugin", "Audio routing established for " + 
                            selectedInputDevice.getProductName());
                        
                        // 3. 启动录音以真正占用设备
                        if (routingAudioRecord != null) {
                            try {
                                routingAudioRecord.startRecording();
                                android.util.Log.i("BaiduAsrPlugin", 
                                    "Routing AudioRecord STARTED to occupy USB microphone");
                                
                                // 验证实际路由的设备
                                if (routingAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                                    AudioDeviceInfo actualDevice = routingAudioRecord.getRoutedDevice();
                                    if (actualDevice != null) {
                                        android.util.Log.i("BaiduAsrPlugin", 
                                            "Actually recording from: " + actualDevice.getProductName() + 
                                            " (type=" + actualDevice.getType() + ", id=" + actualDevice.getId() + ")");
                                        
                                        // 检查是否真的在使用 USB 设备
                                        if (actualDevice.getId() == selectedInputDevice.getId()) {
                                            android.util.Log.i("BaiduAsrPlugin", 
                                                "✓ USB microphone routing confirmed!");
                                        } else {
                                            android.util.Log.w("BaiduAsrPlugin", 
                                                "⚠ Warning: Routing device differs from selected device");
                                        }
                                    }
                                }
                                
                                // 4. 短暂延迟让路由生效
                                try {
                                    Thread.sleep(200);
                                    android.util.Log.d("BaiduAsrPlugin", "Routing delay completed");
                                } catch (InterruptedException e) {
                                    android.util.Log.w("BaiduAsrPlugin", "Routing delay interrupted");
                                }
                                
                                // 5. 停止我们的录音，让百度 SDK 接管
                                if (routingAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                                    routingAudioRecord.stop();
                                    android.util.Log.i("BaiduAsrPlugin", 
                                        "Routing AudioRecord stopped, ready for Baidu SDK");
                                }
                            } catch (Exception e) {
                                android.util.Log.e("BaiduAsrPlugin", 
                                    "Error starting/stopping routing AudioRecord", e);
                            }
                        }
                    } else {
                        android.util.Log.w("BaiduAsrPlugin", "Failed to create routing AudioRecord, " +
                            "recording may use default device");
                    }
                } else {
                    // 非 USB 设备：使用标准方式
                    android.util.Log.i("BaiduAsrPlugin", "Setting up audio routing for non-USB device");
                    boolean audioRecordCreated = createAndHoldRoutingAudioRecord(selectedInputDevice);
                    if (audioRecordCreated) {
                        android.util.Log.i("BaiduAsrPlugin", "Audio routing established for " + 
                            selectedInputDevice.getProductName());
                    } else {
                        android.util.Log.w("BaiduAsrPlugin", "Failed to create routing AudioRecord, " +
                            "recording may use default device");
                    }
                }
            }
        } else {
            // 如果没有选择设备或版本过低，使用系统默认
            if (selectedInputDevice == null) {
                android.util.Log.i("BaiduAsrPlugin", "Start: No device selected, using system default");
            } else {
                android.util.Log.i("BaiduAsrPlugin", "Start: API < 23, cannot set preferred device");
            }
        }
        
        manager.startListening();
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        manager.stopListening();
        releaseRoutingAudioRecord(); // 释放路由 AudioRecord
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        manager.cancel();
        releaseRoutingAudioRecord(); // 释放路由 AudioRecord
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    /**
     * 枚举原生音频输入设备，提供稳定的信息（type、productName、id/hash）。
     */
    @PluginMethod
    public void listInputs(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            com.getcapacitor.JSArray arr = new com.getcapacitor.JSArray();
            if (audioManager != null) {
                AudioDeviceInfo[] inputs;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // 方法1: 切换音频模式来触发重新扫描
                    int originalMode = audioManager.getMode();
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {}
                    audioManager.setMode(originalMode);

// 延迟后再查询
                    new Handler().postDelayed(() -> {
                        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
                        for (AudioDeviceInfo device : devices) {
                            android.util.Log.d("AudioTest", "Device: " + device.getProductName() + ", Type: " + device.getType());
                        }
                    }, 1000);


                    inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
                    
                    // 添加详细日志用于诊断 USB 麦克风扫描问题
                    android.util.Log.i("BaiduAsrPlugin", 
                        "Enumerating audio input devices: found " + inputs.length + " devices");
                    
                    // 检查权限状态
                    boolean hasPermission = isAudioGranted();
                    android.util.Log.i("BaiduAsrPlugin", 
                        "RECORD_AUDIO permission granted: " + hasPermission);


                    for ( AudioDeviceInfo ad :inputs){
                        createAndHoldRoutingAudioRecord(ad);
                    }
                    
                } else {
                    inputs = new AudioDeviceInfo[0];
                    android.util.Log.w("BaiduAsrPlugin", 
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
                    
                    // 详细日志：记录每个设备的完整信息
                    android.util.Log.i("BaiduAsrPlugin", 
                        String.format("Device: name='%s', type=%d, id=%d, address='%s', isSource=%s",
                            productName, type, id, address, isSource));
                    
                    // 特别标记 USB 设备
                    boolean isUsbDevice = (type == AudioDeviceInfo.TYPE_USB_DEVICE || 
                                         type == AudioDeviceInfo.TYPE_USB_HEADSET);
                    if (isUsbDevice) {
                        android.util.Log.i("BaiduAsrPlugin", 
                            "✓ USB device detected: " + productName + " (type=" + type + ")");
                    }
                    
                    JSObject o = new JSObject();
                    o.put("type", type);
                    o.put("label", productName);
                    // 构造稳定ID：type + productName + address/hashCode
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
                
                android.util.Log.i("BaiduAsrPlugin", 
                    "Device enumeration complete: " + arr.length() + " devices returned to frontend");
            } else {
                android.util.Log.e("BaiduAsrPlugin", "AudioManager is null, cannot enumerate devices");
            }
            ret.put("inputs", arr);
            call.resolve(ret);
        } catch (Exception e) {
            android.util.Log.e("BaiduAsrPlugin", "Failed to list inputs", e);
            call.reject("Failed to list inputs: " + e.getMessage());
        }
    }

    /**
     * 选择期望的输入设备（尽力而为）。在 Android 12+ 上尝试设置通信设备，以影响录音路由。
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
                android.util.Log.i("BaiduAsrPlugin", "Device selected: " + 
                    match.getProductName() + " (type=" + match.getType() + 
                    ", id=" + match.getId() + ")");
                
                // 验证设备是否为输入设备
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !match.isSource()) {
                    android.util.Log.w("BaiduAsrPlugin", "Warning: Selected device is not an audio input source");
                }
            } else {
                android.util.Log.w("BaiduAsrPlugin", "Device not found with stableId: " + stableId);
            }
            
            JSObject ret = new JSObject();
            ret.put("ok", match != null);
            ret.put("applied", match != null); // 选择成功即视为已应用（实际应用在 start() 时）
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
     * 验证并更新选中的设备引用（确保设备仍然可用）
     * @return 设备是否仍然可用
     */
    private boolean validateSelectedDevice() {
        if (selectedInputDevice == null || audioManager == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        
        try {
            // 验证设备是否仍然可用
            AudioDeviceInfo[] currentDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo dev : currentDevices) {
                if (dev.getId() == selectedInputDevice.getId()) {
                    // 更新设备引用（避免使用过期的设备对象）
                    selectedInputDevice = dev;
                    android.util.Log.d("BaiduAsrPlugin", "Device validated: " + 
                        dev.getProductName() + " (id=" + dev.getId() + ")");
                    return true;
                }
            }
            
            android.util.Log.w("BaiduAsrPlugin", "Selected device no longer available, device may have been disconnected");
            return false;
        } catch (Exception e) {
            android.util.Log.e("BaiduAsrPlugin", "validateSelectedDevice failed", e);
            return false;
        }
    }
    
    /**
     * 创建并保持一个 AudioRecord 实例用于设备路由
     * 在录音期间保持这个实例不释放，以强制系统使用指定的设备
     * @param device 目标输入设备
     * @return 是否成功创建和设置
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean createAndHoldRoutingAudioRecord(AudioDeviceInfo device) {
        try {
            // 检查设备是否支持该配置
            if (!device.isSource()) {
                android.util.Log.w("BaiduAsrPlugin", "Device is not an input device");
                return false;
            }
            
            // 判断是否为 USB 设备
            int deviceType = device.getType();
            boolean isUsbDevice = (deviceType == AudioDeviceInfo.TYPE_USB_DEVICE || 
                                  deviceType == AudioDeviceInfo.TYPE_USB_HEADSET);
            
            // 根据设备类型选择音频源优先级
            // USB 设备优先使用 MIC (source=1)，与百度 SDK 保持一致
            // 其他设备也优先使用 MIC，因为百度 SDK 使用 MIC
            int[] audioSources;
            if (isUsbDevice) {
                android.util.Log.i("BaiduAsrPlugin", "USB device detected, prioritizing MIC (source=1) to match Baidu SDK");
                audioSources = new int[]{
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,  // 专为语音识别优化
                        MediaRecorder.AudioSource.UNPROCESSED,        // 无处理原始音频（如果支持）
                    MediaRecorder.AudioSource.MIC,                // 标准麦克风（百度 SDK 使用）
                    MediaRecorder.AudioSource.DEFAULT             // 系统默认
                };
            } else {
                // 非 USB 设备也优先使用 MIC，保持一致性
                audioSources = new int[]{
                    MediaRecorder.AudioSource.MIC,                // 标准麦克风（百度 SDK 使用）
                        MediaRecorder.AudioSource.UNPROCESSED,        // 无处理原始音频（如果支持）
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,  // 专为语音识别优化
                    MediaRecorder.AudioSource.DEFAULT             // 系统默认
                };
            }
            
            int sampleRate = 16000; // 使用百度 SDK 常用的采样率，强制固定为16k
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            
            // 不再跟随设备首选采样率，避免与 SDK/服务端不一致导致无效语音
            
            // 计算缓冲区大小
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                android.util.Log.e("BaiduAsrPlugin", "Invalid buffer size");
                return false;
            }
            
            // 尝试不同的音频源创建 AudioRecord
            for (int audioSource : audioSources) {
                try {
                    AudioRecord.Builder builder = new AudioRecord.Builder()
                        .setAudioSource(audioSource)
                        .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build())
                        .setBufferSizeInBytes(bufferSize * 2);
                    
                    routingAudioRecord = builder.build();
                    
                    if (routingAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        routingAudioRecord.release();
                        routingAudioRecord = null;
                        continue;
                    }
                    
                    // 设置首选设备
                    boolean success = routingAudioRecord.setPreferredDevice(device);
                    if (success) {
                        // 验证设备是否已设置
                        AudioDeviceInfo preferred = routingAudioRecord.getPreferredDevice();
                        if (preferred != null && preferred.getId() == device.getId()) {
                            // 注意：我们只保持 AudioRecord 实例，不启动录音
                            // 这样可以影响音频路由，但不会与百度 SDK 的 AudioRecord 冲突
                            android.util.Log.i("BaiduAsrPlugin", 
                                "Routing AudioRecord created (source=" + audioSource + 
                                ", device=" + device.getProductName() + ")");
                            
                            // 获取实际的路由设备（可能与首选设备不同）
                            AudioDeviceInfo routingDevice = routingAudioRecord.getRoutedDevice();
                            if (routingDevice != null) {
                                android.util.Log.i("BaiduAsrPlugin", 
                                    "Current routing device: " + routingDevice.getProductName() + 
                                    " (type=" + routingDevice.getType() + ")");
                            }
                            return true;
                        }
                    }
                    
                    // 如果设置失败，释放并尝试下一个音频源
                    routingAudioRecord.release();
                    routingAudioRecord = null;
                } catch (Exception e) {
                    android.util.Log.w("BaiduAsrPlugin", "Failed with audio source " + audioSource + ": " + e.getMessage());
                    if (routingAudioRecord != null) {
                        try {
                            routingAudioRecord.release();
                        } catch (Exception ignored) {}
                        routingAudioRecord = null;
                    }
                }
            }
            
            android.util.Log.e("BaiduAsrPlugin", "Failed to create routing AudioRecord with any audio source");
            return false;
        } catch (Exception e) {
            android.util.Log.e("BaiduAsrPlugin", "Failed to create routing AudioRecord", e);
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
                // 检查并停止录音（如果正在录音）
                if (routingAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    routingAudioRecord.stop();
                    android.util.Log.i("BaiduAsrPlugin", "Routing AudioRecord stopped before release");
                }
                routingAudioRecord.release();
                android.util.Log.i("BaiduAsrPlugin", "Routing AudioRecord released");
            } catch (Exception e) {
                android.util.Log.w("BaiduAsrPlugin", "Error releasing routing AudioRecord: " + e.getMessage());
            } finally {
                routingAudioRecord = null;
            }
        }
    }
    
    /**
     * 应用选中的设备为通信设备（Android 12+）
     * 注意：可能失败（某些 USB 设备不支持通信设备），但不影响后续流程
     */
    @RequiresApi(api = Build.VERSION_CODES.S)
    private boolean applySelectedDevice() {
        if (audioManager == null || selectedInputDevice == null) {
            android.util.Log.w("BaiduAsrPlugin", "Cannot apply device: audioManager or device is null");
            return false;
        }
        
        try {
            boolean success = audioManager.setCommunicationDevice(selectedInputDevice);
            android.util.Log.i("BaiduAsrPlugin", 
                "setCommunicationDevice: " + selectedInputDevice.getProductName() + 
                " (type=" + selectedInputDevice.getType() + ", id=" + selectedInputDevice.getId() + 
                ") -> " + success);
            
            // 验证是否设置成功
            if (success) {
                AudioDeviceInfo currentCommDevice = audioManager.getCommunicationDevice();
                if (currentCommDevice != null && currentCommDevice.getId() == selectedInputDevice.getId()) {
                    android.util.Log.i("BaiduAsrPlugin", 
                        "✓ Communication device verified: " + currentCommDevice.getProductName());
                }
            }
            
            return success;
        } catch (IllegalArgumentException e) {
            // 某些 USB 设备不支持作为通信设备（例如：invalid portID）
            android.util.Log.w("BaiduAsrPlugin", 
                "setCommunicationDevice failed (device may not support communication mode): " + 
                e.getMessage());
            return false;
        } catch (Exception e) {
            android.util.Log.e("BaiduAsrPlugin", "setCommunicationDevice failed", e);
            return false;
        }
    }
    

    /**
     * 获取当前通信设备信息（调试用）
     */
    @PluginMethod
    public void getCurrentDevice(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                ret.put("available", false);
                call.resolve(ret);
                return;
            }
            AudioDeviceInfo current = audioManager.getCommunicationDevice();
            if (current != null) {
                ret.put("available", true);
                ret.put("type", current.getType());
                CharSequence pn = current.getProductName();
                ret.put("label", pn != null ? pn.toString() : "");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ret.put("address", current.getAddress());
                }
            } else {
                ret.put("available", false);
            }
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to get current device: " + e.getMessage());
        }
    }

    /**
     * 获取当前实际路由的录音设备（不使用通信设备 API）
     * 优先使用 routingAudioRecord.getRoutedDevice()，否则回落到 selectedInputDevice
     */
    @PluginMethod
    public void getRoutedInput(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioDeviceInfo routed = null;
                if (routingAudioRecord != null) {
                    try {
                        routed = routingAudioRecord.getRoutedDevice();
                    } catch (Throwable ignored) {}
                }
                if (routed != null) {
                    ret.put("available", true);
                    ret.put("type", routed.getType());
                    CharSequence pn = routed.getProductName();
                    ret.put("label", pn != null ? pn.toString() : "");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ret.put("address", routed.getAddress());
                    }
                    call.resolve(ret);
                    return;
                }
                // 回落：返回已选择的设备（可能与实际路由不同，但用于前端显示）
                if (selectedInputDevice != null) {
                    ret.put("available", true);
                    ret.put("type", selectedInputDevice.getType());
                    CharSequence pn = selectedInputDevice.getProductName();
                    ret.put("label", pn != null ? pn.toString() : "");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ret.put("address", selectedInputDevice.getAddress());
                    }
                    ret.put("fallback", true);
                    call.resolve(ret);
                    return;
                }
            }
            ret.put("available", false);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to get routed input: " + e.getMessage());
        }
    }

    /**
     * 设置关键词列表
     * @param call 包含 keywords 数组，每个元素有：
     *   - keyword: 关键词文本
     *   - action: 匹配后的动作名称
     *   - mode: 匹配模式 (EXACT/FUZZY/REGEX/PHONETIC)
     *   - aliases: 别名数组（可选）
     *   - metadata: 额外元数据（可选）
     */
    @PluginMethod
    public void setKeywords(PluginCall call) {
        try {
            org.json.JSONArray keywords = new org.json.JSONArray(call.getArray("keywords").toString());
            manager.setKeywords(keywords);
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to set keywords: " + e.getMessage());
        }
    }

    /**
     * 启用/禁用关键词过滤
     * 如果启用，只有匹配到关键词的识别结果才会返回
     */
    @PluginMethod
    public void setKeywordFilter(PluginCall call) {
        Boolean enabled = call.getBoolean("enabled", false);
        if (enabled == null) enabled = false;
        manager.setKeywordFilterEnabled(enabled);
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    /**
     * 设置关键词匹配置信度阈值
     */
    @PluginMethod
    public void setKeywordConfidence(PluginCall call) {
        Float threshold = call.getFloat("threshold");
        if (threshold == null) threshold = 0.6f;
        manager.setKeywordConfidenceThreshold(threshold);
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    /**
     * 诊断：检查 USB 设备列表（通过 USB Host API）
     * 用于诊断某些 USB 麦克风是否被系统识别但未被识别为音频设备
     */
    @PluginMethod
    public void diagnoseUsbDevices(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            com.getcapacitor.JSArray usbDevices = new com.getcapacitor.JSArray();
            
            // 检查 USB Host 是否可用
            boolean usbHostAvailable = getContext().getPackageManager()
                .hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST);
            ret.put("usbHostAvailable", usbHostAvailable);
            
            if (!usbHostAvailable) {
                android.util.Log.w("BaiduAsrPlugin", 
                    "USB Host not available on this device");
                ret.put("usbDevices", usbDevices);
                ret.put("message", "USB Host not available on this device");
                call.resolve(ret);
                return;
            }
            
            // 获取 USB Manager
            UsbManager usbManager = (UsbManager) getContext().getSystemService(android.content.Context.USB_SERVICE);
            if (usbManager == null) {
                android.util.Log.w("BaiduAsrPlugin", "UsbManager is null");
                ret.put("usbDevices", usbDevices);
                ret.put("message", "UsbManager not available");
                call.resolve(ret);
                return;
            }
            
            // 获取 USB 设备列表
            java.util.HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            android.util.Log.i("BaiduAsrPlugin", 
                "Found " + deviceList.size() + " USB devices via USB Host API");
            
            int devicesWithPermission = 0;
            int devicesWithoutPermission = 0;
            
            for (UsbDevice device : deviceList.values()) {
                JSObject deviceInfo = new JSObject();
                
                // 基本信息（不需要权限）
                deviceInfo.put("vendorId", device.getVendorId());
                deviceInfo.put("productId", device.getProductId());
                deviceInfo.put("deviceName", device.getDeviceName());
                deviceInfo.put("deviceClass", device.getDeviceClass());
                
                // 检查权限
                boolean hasPermission = usbManager.hasPermission(device);
                deviceInfo.put("hasPermission", hasPermission);
                
                // 详细信息（需要权限）
                if (hasPermission) {
                    devicesWithPermission++;
                    try {
                        deviceInfo.put("manufacturerName", device.getManufacturerName());
                        deviceInfo.put("productName", device.getProductName());
                        deviceInfo.put("version", device.getVersion());
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            String serial = device.getSerialNumber();
                            if (serial != null) {
                                deviceInfo.put("serialNumber", serial);
                            }
                        }
                        
                        android.util.Log.i("BaiduAsrPlugin", 
                            String.format("USB Device (with permission): VID=0x%04X, PID=0x%04X, name=%s, class=%d",
                                device.getVendorId(),
                                device.getProductId(),
                                device.getProductName(),
                                device.getDeviceClass()));
                    } catch (SecurityException e) {
                        android.util.Log.w("BaiduAsrPlugin", 
                            "SecurityException accessing device details (unexpected): " + e.getMessage());
                        deviceInfo.put("error", "Failed to access device details: " + e.getMessage());
                    }
                } else {
                    devicesWithoutPermission++;
                    deviceInfo.put("manufacturerName", "需要权限");
                    deviceInfo.put("productName", "需要权限");
                    deviceInfo.put("version", "需要权限");
                    
                    android.util.Log.i("BaiduAsrPlugin", 
                        String.format("USB Device (no permission): VID=0x%04X, PID=0x%04X, name=%s, class=%d",
                            device.getVendorId(),
                            device.getProductId(),
                            device.getDeviceName(),
                            device.getDeviceClass()));
                }
                
                usbDevices.put(deviceInfo);
            }
            
            // 添加统计信息
            ret.put("devicesWithPermission", devicesWithPermission);
            ret.put("devicesWithoutPermission", devicesWithoutPermission);
            
            if (devicesWithoutPermission > 0) {
                ret.put("permissionNote", 
                    devicesWithoutPermission + " 个设备需要 USB 权限才能查看详细信息。这通常不影响音频设备的使用，因为音频设备由系统自动管理。");
                android.util.Log.i("BaiduAsrPlugin", 
                    devicesWithoutPermission + " devices require USB permission for detailed info");
            }
            
            ret.put("usbDevices", usbDevices);
            ret.put("count", deviceList.size());
            ret.put("message", "USB device enumeration complete");
            
            android.util.Log.i("BaiduAsrPlugin", 
                "USB device diagnosis complete: " + deviceList.size() + " devices found");
            
            call.resolve(ret);
        } catch (SecurityException e) {
            // USB 权限相关的安全异常
            android.util.Log.w("BaiduAsrPlugin", 
                "SecurityException accessing USB devices: " + e.getMessage());
            JSObject ret = new JSObject();
            ret.put("usbHostAvailable", true);
            ret.put("usbDevices", new com.getcapacitor.JSArray());
            ret.put("count", 0);
            ret.put("error", "USB权限不足");
            ret.put("message", "需要 USB 权限才能访问设备列表。注意：大多数 USB 音频设备不需要此权限，因为系统会自动管理。");
            ret.put("errorDetails", e.getMessage());
            call.resolve(ret); // 使用 resolve 而不是 reject，因为这不是致命错误
        } catch (Exception e) {
            android.util.Log.e("BaiduAsrPlugin", "Failed to diagnose USB devices", e);
            call.reject("Failed to diagnose USB devices: " + e.getMessage());
        }
    }

    /**
     * 请求 USB 设备权限（用于诊断）
     * 注意：大多数 USB 音频设备不需要此权限，因为系统会自动管理
     * 
     * @param call 包含 deviceName (设备名称，如 "/dev/bus/usb/001/005")
     */
    @PluginMethod
    public void requestUsbPermission(PluginCall call) {
        String deviceName = call.getString("deviceName");
        if (deviceName == null || deviceName.isEmpty()) {
            call.reject("deviceName is required");
            return;
        }
        
        try {
            UsbManager usbManager = (UsbManager) getContext().getSystemService(android.content.Context.USB_SERVICE);
            if (usbManager == null) {
                call.reject("UsbManager not available");
                return;
            }
            
            // 查找设备
            java.util.HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            UsbDevice targetDevice = null;
            for (UsbDevice device : deviceList.values()) {
                if (device.getDeviceName().equals(deviceName)) {
                    targetDevice = device;
                    break;
                }
            }
            
            if (targetDevice == null) {
                call.reject("USB device not found: " + deviceName);
                return;
            }
            
            // 检查是否已有权限
            if (usbManager.hasPermission(targetDevice)) {
                android.util.Log.i("BaiduAsrPlugin", 
                    "USB permission already granted for: " + deviceName);
                JSObject ret = new JSObject();
                ret.put("ok", true);
                ret.put("granted", true);
                ret.put("message", "Permission already granted");
                call.resolve(ret);
                return;
            }
            
            // 请求权限
            android.util.Log.i("BaiduAsrPlugin", 
                "Requesting USB permission for: " + deviceName);
            
            PendingIntent permissionIntent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionIntent = PendingIntent.getBroadcast(
                    getContext(), 
                    0, 
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                );
            } else {
                permissionIntent = PendingIntent.getBroadcast(
                    getContext(), 
                    0, 
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_UPDATE_CURRENT
                );
            }
            
            pendingUsbPermissionCall = call;
            usbManager.requestPermission(targetDevice, permissionIntent);
            
            // 注意：权限请求是异步的，结果会通过广播接收器返回
            // 这里不立即 resolve/reject，等待广播接收器处理
            
        } catch (Exception e) {
            android.util.Log.e("BaiduAsrPlugin", "Failed to request USB permission", e);
            call.reject("Failed to request USB permission: " + e.getMessage());
        }
    }

    /**
     * 获取 Android 版本信息
     */
    @PluginMethod
    public void getAndroidVersion(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("version", Build.VERSION.RELEASE); // 例如 "11", "12"
        ret.put("sdkInt", Build.VERSION.SDK_INT); // API 级别，例如 30, 31
        ret.put("codename", Build.VERSION.CODENAME); // 开发代号
        ret.put("incremental", Build.VERSION.INCREMENTAL); // 内部版本号
        call.resolve(ret);
    }
}


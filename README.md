## Capacitor + Sherpa-ONNX 语音识别 Demo（Android）

本示例为 Android 平板应用的离线语音识别 Demo，使用 Capacitor 集成 Sherpa-ONNX 关键词识别引擎。

### 特性

- ✅ 完全离线运行，无需网络连接
- ✅ 低延迟实时关键词检测
- ✅ 支持自定义关键词列表
- ✅ 可配置检测阈值和采样率
- ✅ 支持多麦克风设备选择
- ✅ 内置中文唤醒词模型

### 环境要求

- Node.js 18+
- Android Studio（含 Android SDK）
- Java 17
- Android SDK API 23+

### 安装与运行

```bash
npm install
npm run build
npx cap sync android
npx cap open android
```

### 模型文件

模型文件已包含在 `android/app/src/main/assets/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile/` 目录中，包含：

- `encoder-epoch-12-avg-2-chunk-16-left-64.onnx` - 编码器模型
- `encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx` - 量化编码器
- `decoder-epoch-12-avg-2-chunk-16-left-64.onnx` - 解码器模型
- `joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx` - 连接层模型
- `keywords.txt` - 内置关键词列表
- `tokens.txt` - 词汇表

### 权限配置

`AndroidManifest.xml` 已添加必要权限：
- `RECORD_AUDIO` - 录音权限（必需）
- `INTERNET` - 网络权限（可选）
- `MODIFY_AUDIO_SETTINGS` - 音频设置权限
- `BLUETOOTH_CONNECT` - 蓝牙设备连接（Android 12+）

插件会在首次调用 `init()` 时自动请求麦克风权限。

### 前端使用

#### 基本用法

```typescript
import { SherpaOnnx, KeywordDetectedEvent } from './sherpa-onnx-plugin'

// 1. 初始化引擎
const result = await SherpaOnnx.init({
  keywords: ['打开灯', '关闭灯', '播放音乐'], // 关键词列表（可选，也可使用模型内置关键词）
  sampleRate: 16000,  // 采样率，默认 16000
  numThreads: 1,      // 线程数，默认 1
  threshold: 0.2      // 检测阈值 0.0-1.0，默认 0.2
})

if (!result.ok) {
  console.error('初始化失败:', result.message)
  return
}

// 2. 监听关键词检测事件
SherpaOnnx.addListener('onKeywordDetected', (event: KeywordDetectedEvent) => {
  console.log(`检测到关键词: ${event.keyword}`)
  console.log(`置信度: ${(event.confidence * 100).toFixed(1)}%`)
  console.log(`时间戳: ${new Date(event.timestamp).toLocaleTimeString()}`)
  
  // 根据关键词执行相应操作
  switch (event.keyword) {
    case '打开灯':
      // 执行开灯操作
      break
    case '关闭灯':
      // 执行关灯操作
      break
  }
})

// 3. 监听错误事件
SherpaOnnx.addListener('onError', (error) => {
  console.error(`错误: ${error.code} - ${error.message}`)
})

// 4. 监听就绪事件
SherpaOnnx.addListener('onReady', () => {
  console.log('引擎已就绪')
})

// 5. 开始识别
await SherpaOnnx.start()

// 6. 停止识别
await SherpaOnnx.stop()
```

#### 动态更新关键词

```typescript
// 初始化时使用空列表或内置关键词
await SherpaOnnx.init({ threshold: 0.2 })

// 运行时更新关键词
await SherpaOnnx.updateKeywords({
  keywords: ['新指令1', '新指令2', '新指令3']
})
```

#### 调整检测阈值

```typescript
// 设置更高的阈值（减少误触发）
await SherpaOnnx.setThreshold({ threshold: 0.6 })

// 设置更低的阈值（提高敏感度）
await SherpaOnnx.setThreshold({ threshold: 0.1 })
```

#### 麦克风设备选择

```typescript
// 1. 枚举可用麦克风设备
const { inputs } = await SherpaOnnx.listInputs()
console.log('可用设备:', inputs)

// 2. 选择指定设备
const device = inputs.find(d => d.type === AudioDeviceInfo.TYPE_USB_DEVICE)
if (device) {
  await SherpaOnnx.selectInput({ stableId: device.stableId })
}

// 3. 开始识别（将使用选定的设备）
await SherpaOnnx.start()
```

#### 获取状态

```typescript
const status = await SherpaOnnx.getStatus()
console.log('运行中:', status.isRunning)
console.log('已暂停:', status.isPaused)
console.log('关键词数:', status.keywordsCount)
console.log('当前阈值:', status.threshold)
console.log('采样率:', status.sampleRate)
```

### API 参考

#### `init(options?)`

初始化 Sherpa-ONNX 引擎。

**参数：**
- `modelPath?: string` - 模型文件路径（可选，默认从 assets 加载）
- `keywords?: string[]` - 关键词列表（可选，可传空数组使用模型内置关键词）
- `sampleRate?: number` - 采样率，默认 16000
- `numThreads?: number` - 线程数，默认 1
- `threshold?: number` - 检测阈值 0.0-1.0，默认 0.2

**返回：** `Promise<{ ok: boolean; message?: string }>`

#### `start()`

开始语音识别。

**返回：** `Promise<{ ok: boolean }>`

#### `stop()`

停止语音识别。

**返回：** `Promise<{ ok: boolean }>`

#### `updateKeywords(options)`

更新关键词列表。

**参数：**
- `keywords: string[]` - 新的关键词列表

**返回：** `Promise<{ ok: boolean }>`

#### `setThreshold(options)`

设置检测阈值。

**参数：**
- `threshold: number` - 阈值 0.0-1.0

**返回：** `Promise<{ ok: boolean }>`

#### `getStatus()`

获取引擎状态。

**返回：** `Promise<{ isRunning: boolean; isPaused: boolean; keywordsCount: number; threshold: number; sampleRate: number }>`

#### `listInputs()`

枚举可用的音频输入设备。

**返回：** `Promise<{ inputs: Array<{ stableId: string; type: number; label?: string; address?: string; id?: number; isSource?: boolean }> }>`

#### `selectInput(options)`

选择音频输入设备。

**参数：**
- `stableId: string` - 设备的稳定 ID（从 `listInputs()` 获取）

**返回：** `Promise<{ ok: boolean; applied?: boolean; deviceName?: string; deviceType?: number; deviceId?: number }>`

### 事件

#### `onKeywordDetected`

当检测到关键词时触发。

**事件数据：**
```typescript
{
  keyword: string      // 检测到的关键词
  confidence: number   // 置信度 0.0-1.0
  timestamp: number    // 时间戳（毫秒）
}
```

#### `onError`

当发生错误时触发。

**事件数据：**
```typescript
{
  code: string    // 错误代码
  message: string // 错误消息
}
```

#### `onReady`

当引擎就绪时触发。

### 原生实现位置

- **TypeScript 插件接口：** `src/sherpa-onnx-plugin.ts`
- **Android 插件实现：** `android/app/src/main/java/com/example/speechrec/sherpaonnx/SherpaOnnxPlugin.java`
- **Sherpa-ONNX 管理器：** `android/app/src/main/java/com/example/speechrec/sherpaonnx/SherpaOnnxManager.java`

### 示例代码

完整的使用示例请参考：
- `src/sherpa-onnx-example.ts` - 基本用法和高级示例
- `src/sherpa-onnx-page.ts` - UI 页面实现

### 最佳实践

1. **生命周期管理**
   - 应用暂停时自动停止识别
   - 应用恢复时重新初始化（如需要）
   - 销毁时释放资源

2. **阈值调优**
   - 从较低阈值（0.2）开始测试
   - 根据误触发情况逐步提高阈值
   - 不同环境（安静/嘈杂）可能需要不同阈值

3. **关键词设计**
   - 使用2-4字的中文短语效果较好
   - 避免发音相似的词语
   - 可通过 `updateKeywords()` 动态调整

4. **性能优化**
   - 根据设备性能调整 `numThreads`
   - 使用 `int8` 量化模型可减少内存占用
   - 采样率 16000Hz 通常足够，44100Hz 需要更多资源

5. **设备选择**
   - 在开始识别前选择正确的麦克风设备
   - 定期刷新设备列表（设备可能被拔插）
   - 保存用户的首选设备到 localStorage

### 注意事项

- 首次初始化可能需要几秒钟加载模型
- 确保模型文件在 assets 目录中且路径正确
- 测试时建议在目标设备上验证，模拟器可能无法正常录音
- 某些设备的电源管理可能影响录音，需要在后台运行时保持前台服务

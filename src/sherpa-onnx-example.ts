/**
 * Sherpa-ONNX 使用示例
 * 
 * 这个文件展示了如何使用基于 sherpa-onnx 的指令识别功能
 */

import { SherpaOnnx, KeywordDetectedEvent, ErrorEvent } from './sherpa-onnx-plugin'

// 示例：基本使用
export async function basicExample() {
  try {
    // 1. 初始化
    const result = await SherpaOnnx.init({
      keywords: ['打开灯', '关闭灯', '播放音乐', '停止播放'],
      threshold: 0.6
    })
    
    if (!result.ok) {
      console.error('初始化失败')
      return
    }
    
    // 2. 监听事件
    SherpaOnnx.addListener('onKeywordDetected', (event: KeywordDetectedEvent) => {
      console.log(`检测到关键词: ${event.keyword}, 置信度: ${event.confidence}`)
      handleCommand(event.keyword)
    })
    
    SherpaOnnx.addListener('onError', (error: ErrorEvent) => {
      console.error(`错误: ${error.code} - ${error.message}`)
    })
    
    // 3. 开始识别
    await SherpaOnnx.start()
    
  } catch (error) {
    console.error('设置失败:', error)
  }
}

// 示例：动态更新关键词
export async function dynamicKeywordsExample() {
  // 初始化时使用空关键词列表
  await SherpaOnnx.init({ threshold: 0.2 })
  
  // 稍后动态添加关键词
  await SherpaOnnx.updateKeywords({
    keywords: ['新指令1', '新指令2']
  })
  
  // 开始识别
  await SherpaOnnx.start()
  
  // 运行时更新关键词
  setTimeout(async () => {
    await SherpaOnnx.updateKeywords({
      keywords: ['更新的指令1', '更新的指令2']
    })
  }, 5000)
}

// 示例：完整的指令识别系统
export class CommandRecognitionSystem {
  private commands: Map<string, () => void> = new Map()
  
  constructor() {
    this.setupListeners()
  }
  
  async initialize(keywords: string[]) {
    const result = await SherpaOnnx.init({
      keywords,
      threshold: 0.65,
      sampleRate: 16000,
      numThreads: 2
    })
    
    if (!result.ok) {
      throw new Error('初始化失败: ' + result.message)
    }
  }
  
  async start() {
    await SherpaOnnx.start()
  }
  
  async stop() {
    await SherpaOnnx.stop()
  }
  
  registerCommand(keyword: string, handler: () => void) {
    this.commands.set(keyword, handler)
  }
  
  private setupListeners() {
    SherpaOnnx.addListener('onKeywordDetected', (event: KeywordDetectedEvent) => {
      const handler = this.commands.get(event.keyword)
      if (handler) {
        console.log(`执行命令: ${event.keyword}`)
        handler()
      } else {
        console.warn(`未注册的命令: ${event.keyword}`)
      }
    })
    
    SherpaOnnx.addListener('onError', (error: ErrorEvent) => {
      console.error('识别错误:', error)
    })
  }
  
  async updateCommands(keywords: string[]) {
    await SherpaOnnx.updateKeywords({ keywords })
    
    // 清理未使用的命令处理器
    const activeKeywords = new Set(keywords)
    for (const keyword of this.commands.keys()) {
      if (!activeKeywords.has(keyword)) {
        this.commands.delete(keyword)
      }
    }
  }
  
  async adjustSensitivity(threshold: number) {
    await SherpaOnnx.setThreshold({ threshold })
  }
  
  async getStatus() {
    return await SherpaOnnx.getStatus()
  }
}

// 使用示例
export async function fullSystemExample() {
  const system = new CommandRecognitionSystem()
  
  // 注册命令处理器
  system.registerCommand('打开灯', () => {
    console.log('💡 灯已打开')
    // 实际控制灯的代码
  })
  
  system.registerCommand('关闭灯', () => {
    console.log('🌙 灯已关闭')
    // 实际控制灯的代码
  })
  
  system.registerCommand('播放音乐', () => {
    console.log('🎵 开始播放音乐')
    // 实际播放音乐的代码
  })
  
  system.registerCommand('停止播放', () => {
    console.log('⏹️ 停止播放')
    // 实际停止播放的代码
  })
  
  // 初始化并开始
  await system.initialize([
    '打开灯',
    '关闭灯',
    '播放音乐',
    '停止播放'
  ])
  
  await system.start()
  
  // 示例：运行时调整灵敏度
  setTimeout(async () => {
    await system.adjustSensitivity(0.7) // 提高阈值，减少误触发
  }, 10000)
}

// 辅助函数：处理命令
function handleCommand(keyword: string) {
  switch (keyword) {
    case '打开灯':
      console.log('执行: 打开灯')
      break
    case '关闭灯':
      console.log('执行: 关闭灯')
      break
    case '播放音乐':
      console.log('执行: 播放音乐')
      break
    case '停止播放':
      console.log('执行: 停止播放')
      break
    default:
      console.log(`未知命令: ${keyword}`)
  }
}


/**
 * 完整的指令识别系统
 */
import { SherpaOnnx, KeywordDetectedEvent, ErrorEvent } from '../capacitor/plugins'

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


/**
 * 完整的指令识别系统
 */
import { SherpaOnnx, KeywordDetectedEvent, ErrorEvent } from '../capacitor/plugins'

/**
 * 命令类型定义
 */
export interface CommandType {
  /** 命令的唯一标识 */
  id: string
  /** 命令的显示名称 */
  name: string
  /** 该命令对应的所有关键词 */
  keywords: string[]
  /** 命令描述 */
  description?: string
}

/**
 * 关键词到命令的映射配置
 */
export interface CommandMapping {
  /** 命令类型列表 */
  commands: CommandType[]
}

export class CommandRecognitionSystem {
  private commands: Map<string, () => void> = new Map()
  private keywordToCommandMap: Map<string, string> = new Map()
  private commandConfigs: Map<string, CommandType> = new Map()

  constructor() {
    this.setupListeners()
  }

  /**
   * 初始化系统
   * @param keywords 所有关键词列表
   * @param options 初始化选项
   */
  async initialize(keywords: string[], options?: {
    threshold?: number
    sampleRate?: number
    numThreads?: number
  }) {
    const result = await SherpaOnnx.init({
      keywords,
      threshold: options?.threshold ?? 0.65,
      sampleRate: options?.sampleRate ?? 16000,
      numThreads: options?.numThreads ?? 2
    })

    if (!result.ok) {
      throw new Error('初始化失败: ' + result.message)
    }
  }

  /**
   * 注册命令映射
   * @param mapping 命令映射配置
   */
  setCommandMapping(mapping: CommandMapping) {
    this.commandConfigs.clear()
    this.keywordToCommandMap.clear()

    // 构建关键词到命令的映射
    for (const command of mapping.commands) {
      this.commandConfigs.set(command.id, command)

      // 将每个关键词映射到对应的命令ID
      for (const keyword of command.keywords) {
        this.keywordToCommandMap.set(keyword, command.id)
      }
    }

    console.log(`已注册 ${mapping.commands.length} 个命令类型，覆盖 ${this.keywordToCommandMap.size} 个关键词`)
  }

  /**
   * 注册命令处理器
   * @param commandId 命令ID
   * @param handler 处理器函数
   */
  registerCommand(commandId: string, handler: () => void) {
    if (!this.commandConfigs.has(commandId)) {
      console.warn(`注册失败: 命令ID "${commandId}" 不存在`)
      return
    }

    this.commands.set(commandId, handler)
    console.log(`已注册命令处理器: ${commandId}`)
  }

  /**
   * 获取命令信息
   */
  getCommandInfo(): Map<string, CommandType> {
    return new Map(this.commandConfigs)
  }

  /**
   * 根据关键词获取对应的命令ID
   */
  getCommandIdByKeyword(keyword: string): string | undefined {
    return this.keywordToCommandMap.get(keyword)
  }

  async start() {
    await SherpaOnnx.start()
  }

  async stop() {
    await SherpaOnnx.stop()
  }

  private setupListeners() {
    SherpaOnnx.addListener('onKeywordDetected', (event: KeywordDetectedEvent) => {
      const commandId = this.keywordToCommandMap.get(event.keyword)

      if (!commandId) {
        console.warn(`未映射的关键词: ${event.keyword}`)
        return
      }

      const handler = this.commands.get(commandId)
      if (handler) {
        const commandInfo = this.commandConfigs.get(commandId)
        console.log(`[${commandInfo?.name || commandId}] 检测到关键词: ${event.keyword} (置信度: ${(event.confidence * 100).toFixed(1)}%)`)
        handler()
      } else {
        console.warn(`未注册的命令处理器: ${commandId}`)
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
    for (const keyword of this.keywordToCommandMap.keys()) {
      if (!activeKeywords.has(keyword)) {
        this.keywordToCommandMap.delete(keyword)
      }
    }

    // 清理不再有可用关键词的命令处理器
    const activeCommandIds = new Set(Array.from(this.keywordToCommandMap.values()))
    for (const commandId of this.commands.keys()) {
      if (!activeCommandIds.has(commandId)) {
        this.commands.delete(commandId)
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


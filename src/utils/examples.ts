/**
 * Sherpa-ONNX 使用示例
 */
import { SherpaOnnx, KeywordDetectedEvent, ErrorEvent } from '../capacitor/plugins'
import { CommandRecognitionSystem } from '../services'
import { 
  createCommandMapping, 
  getAllKeywords, 
  findCommandById,
  COMMAND_TYPES 
} from '../services/commands'

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


// 使用命令分类系统示例（新版API）
export async function categorizedCommandExample() {
  const system = new CommandRecognitionSystem()
  
  // 1. 设置命令映射配置
  const mapping = createCommandMapping()
  system.setCommandMapping(mapping)
  
  // 2. 注册每个命令类型的处理器
  system.registerCommand('activate', () => {
    console.log('🎤 语音助手已激活')
    // 执行激活逻辑
  })
  
  system.registerCommand('previous', () => {
    console.log('⏮️ 上一个项目')
    // 执行上一个逻辑
  })
  
  system.registerCommand('next', () => {
    console.log('⏭️ 下一个项目')
    // 执行下一个逻辑
  })
  
  system.registerCommand('restart', () => {
    console.log('🔄 重新开始')
    // 执行重新开始逻辑
  })
  
  system.registerCommand('replay', () => {
    console.log('🔁 重播')
    // 执行重播逻辑
  })
  
  system.registerCommand('pause', () => {
    console.log('⏸️ 暂停')
    // 执行暂停逻辑
  })
  
  system.registerCommand('resume', () => {
    console.log('▶️ 继续')
    // 执行继续逻辑
  })
  
  system.registerCommand('volumeUp', () => {
    console.log('🔊 音量调大')
    // 执行音量调大逻辑
  })
  
  system.registerCommand('volumeDown', () => {
    console.log('🔉 音量调小')
    // 执行音量调小逻辑
  })
  
  // 3. 获取所有关键词并初始化
  const allKeywords = getAllKeywords()
  console.log(`加载了 ${allKeywords.length} 个关键词`)
  
  await system.initialize(allKeywords, {
    threshold: 0.65,
    sampleRate: 16000,
    numThreads: 2
  })
  
  // 4. 开始识别
  await system.start()
  
  // 5. 示例：运行时调整灵敏度
  setTimeout(async () => {
    await system.adjustSensitivity(0.7)
  }, 10000)
  
  return system
}

// 高级示例：动态命令管理
export async function advancedCommandExample() {
  const system = new CommandRecognitionSystem()
  
  // 设置命令映射
  const mapping = createCommandMapping()
  system.setCommandMapping(mapping)
  
  // 获取命令信息
  const commandInfo = system.getCommandInfo()
  console.log('可用命令:')
  commandInfo.forEach((cmd, id) => {
    console.log(`  ${id}: ${cmd.name} (${cmd.keywords.length} 个关键词)`)
  })
  
  // 注册所有命令的通用处理器
  const handlers: Record<string, () => void> = {
    'activate': () => console.log('🎤 激活'),
    'previous': () => console.log('⏮️ 上一个'),
    'next': () => console.log('⏭️ 下一个'),
    'restart': () => console.log('🔄 重新开始'),
    'replay': () => console.log('🔁 重播'),
    'pause': () => console.log('⏸️ 暂停'),
    'resume': () => console.log('▶️ 继续'),
    'volumeUp': () => console.log('🔊 音量+'),
    'volumeDown': () => console.log('🔉 音量-')
  }
  
  // 批量注册处理器
  for (const [commandId, handler] of Object.entries(handlers)) {
    system.registerCommand(commandId, handler)
  }
  
  // 初始化并开始
  await system.initialize(getAllKeywords())
  await system.start()
  
  return system
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


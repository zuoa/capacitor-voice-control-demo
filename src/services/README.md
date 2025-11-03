# 命令识别系统

完整的语音命令识别与分类系统，支持多个关键词映射到同一个动作指令。

## 功能特性

- ✅ **关键词分类**: 将多个关键词归类到同一个命令动作
- ✅ **类型安全**: TypeScript 类型定义完善
- ✅ **动态管理**: 支持运行时更新关键词和命令
- ✅ **灵活配置**: 易于扩展和维护的命令配置

## 架构

### 核心组件

1. **CommandRecognitionSystem**: 命令识别系统主类
2. **commands.ts**: 命令配置和映射定义

### 概念

- **关键词 (Keyword)**: 用户实际说出的语音文本，如 "上一步"、"上一个"、"前一步"
- **命令 (Command)**: 抽象的动作指令，如 "previous"（上一个）
- **映射 (Mapping)**: 关键词到命令的多对一映射关系

## 使用示例

### 基本使用

```typescript
import { CommandRecognitionSystem } from './services'
import { createCommandMapping, getAllKeywords } from './services/commands'

const system = new CommandRecognitionSystem()

// 1. 设置命令映射
const mapping = createCommandMapping()
system.setCommandMapping(mapping)

// 2. 注册命令处理器（使用命令ID而非关键词）
system.registerCommand('previous', () => {
  console.log('执行上一个操作')
})

system.registerCommand('next', () => {
  console.log('执行下一个操作')
})

// 3. 初始化并开始
const keywords = getAllKeywords()
await system.initialize(keywords, {
  threshold: 0.65,
  sampleRate: 16000,
  numThreads: 2
})

await system.start()
```

### 命令映射原理

当用户说出 "上一步"、"上一个"、"前一个" 或 "前一步" 时，系统会：

1. 识别到关键词（例如 "上一步"）
2. 查找关键词对应的命令ID（"previous"）
3. 执行命令ID对应的处理器函数

### 添加新命令

在 `commands.ts` 中添加新的命令类型：

```typescript
export const COMMAND_TYPES: CommandType[] = [
  // ... 现有命令
  {
    id: 'customAction',
    name: '自定义动作',
    description: '执行自定义操作',
    keywords: [
      '自定义关键词1',
      '自定义关键词2'
    ]
  }
]
```

### 获取命令信息

```typescript
// 获取所有命令信息
const commandInfo = system.getCommandInfo()
commandInfo.forEach((cmd, id) => {
  console.log(`${id}: ${cmd.name} (${cmd.keywords.length} 个关键词)`)
})

// 查找关键词对应的命令
import { findCommandByKeyword } from './services/commands'
const cmd = findCommandByKeyword('上一步')
console.log(cmd?.name) // "上一个"

// 根据关键词获取命令ID
const commandId = system.getCommandIdByKeyword('上一步')
console.log(commandId) // "previous"
```

## 命令分类

当前系统支持以下命令类型（共 36 个关键词）：

| 命令ID | 名称 | 关键词数量 |
|--------|------|-----------|
| `activate` | 激活 | 8 |
| `previous` | 上一个 | 4 |
| `next` | 下一个 | 4 |
| `restart` | 重新开始 | 4 |
| `replay` | 重播 | 5 |
| `pause` | 暂停 | 1 |
| `resume` | 继续 | 2 |
| `volumeUp` | 音量调大 | 4 |
| `volumeDown` | 音量调小 | 4 |

## API 参考

### CommandRecognitionSystem

#### setCommandMapping(mapping: CommandMapping)

设置命令映射配置。

#### registerCommand(commandId: string, handler: () => void)

注册命令处理器。使用命令ID而非关键词。

#### initialize(keywords: string[], options?: object)

初始化系统。需要提供所有关键词列表。

#### getCommandInfo(): Map<string, CommandType>

获取所有命令信息。

#### getCommandIdByKeyword(keyword: string): string | undefined

根据关键词获取对应的命令ID。

### 配置函数

#### createCommandMapping(): CommandMapping

创建命令映射配置对象。

#### getAllKeywords(): string[]

获取所有关键词列表。

#### findCommandById(commandId: string): CommandType | undefined

根据命令ID查找命令信息。

#### findCommandByKeyword(keyword: string): CommandType | undefined

根据关键词查找命令信息。

## 迁移指南

### 从旧版迁移

旧版使用关键词作为命令标识：

```typescript
// 旧版
system.registerCommand('上一步', handler)
system.registerCommand('上一个', handler)
system.registerCommand('前一步', handler)
```

新版使用命令ID：

```typescript
// 新版
system.setCommandMapping(createCommandMapping())
system.registerCommand('previous', handler)
// 自动支持 "上一步"、"上一个"、"前一步" 等多个关键词
```

## 优势

1. **代码复用**: 同一处理器函数处理多个相似关键词
2. **易于维护**: 配置集中管理，逻辑清晰
3. **灵活扩展**: 轻松添加新关键词到现有命令
4. **类型安全**: TypeScript 提供完整的类型检查

# 命令分类系统测试

## 测试目的

验证命令分类系统正确地将多个关键词映射到同一个命令。

## 测试场景

### 测试 1: 关键词映射验证

测试每个关键词都能正确映射到对应的命令ID。

```typescript
import { CommandRecognitionSystem } from './src/services'
import { getAllKeywords, findCommandByKeyword } from './src/services/commands'

const system = new CommandRecognitionSystem()
const mapping = createCommandMapping()
system.setCommandMapping(mapping)

// 测试关键词映射
const testCases = [
  { keyword: '上一步', expectedCommandId: 'previous', expectedName: '上一个' },
  { keyword: '上一个', expectedCommandId: 'previous', expectedName: '上一个' },
  { keyword: '前一步', expectedCommandId: 'previous', expectedName: '上一个' },
  { keyword: '前一个', expectedCommandId: 'previous', expectedName: '上一个' },
  
  { keyword: '下一步', expectedCommandId: 'next', expectedName: '下一个' },
  { keyword: '下一个', expectedCommandId: 'next', expectedName: '下一个' },
  { keyword: '后一步', expectedCommandId: 'next', expectedName: '下一个' },
  { keyword: '后一个', expectedCommandId: 'next', expectedName: '下一个' },
  
  { keyword: '再播一次', expectedCommandId: 'replay', expectedName: '重播' },
  { keyword: '重播', expectedCommandId: 'replay', expectedName: '重播' },
  { keyword: '再来一遍', expectedCommandId: 'replay', expectedName: '重播' },
  { keyword: '重新来', expectedCommandId: 'replay', expectedName: '重播' },
  { keyword: '重新播放', expectedCommandId: 'replay', expectedName: '重播' },
  
  { keyword: '你好军哥', expectedCommandId: 'activate', expectedName: '激活' },
  { keyword: '小爱同学', expectedCommandId: 'activate', expectedName: '激活' },
]

for (const testCase of testCases) {
  const commandId = system.getCommandIdByKeyword(testCase.keyword)
  const command = findCommandByKeyword(testCase.keyword)
  
  console.assert(commandId === testCase.expectedCommandId, 
    `❌ 失败: "${testCase.keyword}" 应该映射到 "${testCase.expectedCommandId}"，但得到 "${commandId}"`)
  
  console.assert(command?.name === testCase.expectedName,
    `❌ 失败: "${testCase.keyword}" 的命令名称应该是 "${testCase.expectedName}"，但得到 "${command?.name}"`)
  
  console.log(`✅ ${testCase.keyword} → ${commandId} (${command?.name})`)
}
```

### 测试 2: 命令执行验证

测试当识别到关键词时，正确的处理器被调用。

```typescript
const system = new CommandRecognitionSystem()
const mapping = createCommandMapping()
system.setCommandMapping(mapping)

let previousCalled = 0
let nextCalled = 0
let replayCalled = 0

// 注册处理器
system.registerCommand('previous', () => {
  previousCalled++
  console.log('✅ previous 命令被执行')
})

system.registerCommand('next', () => {
  nextCalled++
  console.log('✅ next 命令被执行')
})

system.registerCommand('replay', () => {
  replayCalled++
  console.log('✅ replay 命令被执行')
})

// 模拟关键词检测
const mockKeywordEvents = [
  { keyword: '上一步', confidence: 0.8, timestamp: Date.now() },
  { keyword: '前一个', confidence: 0.9, timestamp: Date.now() },
  { keyword: '下一步', confidence: 0.85, timestamp: Date.now() },
  { keyword: '后一步', confidence: 0.88, timestamp: Date.now() },
  { keyword: '重播', confidence: 0.92, timestamp: Date.now() },
  { keyword: '再播一次', confidence: 0.87, timestamp: Date.now() },
]

// 手动触发（在实际使用中，这些事件由 SherpaOnnx 触发）
for (const event of mockKeywordEvents) {
  const commandId = system.getCommandIdByKeyword(event.keyword)
  if (commandId) {
    const handler = (system as any).commands.get(commandId)
    if (handler) handler()
  }
}

// 验证调用次数
console.assert(previousCalled === 2, `previous 应该被调用 2 次，但实际调用 ${previousCalled} 次`)
console.assert(nextCalled === 2, `next 应该被调用 2 次，但实际调用 ${nextCalled} 次`)
console.assert(replayCalled === 2, `replay 应该被调用 2 次，但实际调用 ${replayCalled} 次`)

console.log('✅ 所有命令都执行了正确的次数')
```

### 测试 3: 关键词覆盖率验证

验证所有 36 个关键词都被正确映射。

```typescript
const keywords = getAllKeywords()
console.log(`总关键词数: ${keywords.length}`)

const system = new CommandRecognitionSystem()
system.setCommandMapping(createCommandMapping())

let mappedCount = 0
let unmappedKeywords: string[] = []

for (const keyword of keywords) {
  const commandId = system.getCommandIdByKeyword(keyword)
  if (commandId) {
    mappedCount++
  } else {
    unmappedKeywords.push(keyword)
  }
}

console.log(`已映射: ${mappedCount} / ${keywords.length}`)

if (unmappedKeywords.length > 0) {
  console.error('❌ 以下关键词未映射:')
  unmappedKeywords.forEach(kw => console.error(`  - ${kw}`))
} else {
  console.log('✅ 所有关键词都已正确映射')
}
```

## 预期结果

- ✅ 所有测试通过
- ✅ 36 个关键词全部映射到 9 个命令
- ✅ 没有未映射的关键词
- ✅ 命令处理器按预期执行

## 运行测试

```bash
# 编译 TypeScript
npm run build

# 在浏览器控制台运行测试脚本
# 或创建独立的测试文件
```

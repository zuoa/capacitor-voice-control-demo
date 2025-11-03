/**
 * 语音命令配置
 * 定义关键词到命令动作的映射关系
 */
import { CommandMapping, CommandType } from './CommandRecognitionSystem'

/**
 * 定义所有可用的命令类型
 */
export const COMMAND_TYPES: CommandType[] = [
    {
        id: 'activate',
        name: '激活',
        description: '唤醒语音助手',
        keywords: [
            '码小全'
        ]
    },
    {
        id: 'previous',
        name: '上一步',
        description: '返回到上一个项目',
        keywords: [
            '上一步',
            '上一个',
            '前一步',
            '前一个'
        ]
    },
    {
        id: 'next',
        name: '下一步',
        description: '前进到下一个项目',
        keywords: [
            '下一步',
            '下一个',
            '后一步',
            '后一个'
        ]
    },
    {
        id: 'restart',
        name: '重新开始',
        description: '返回到开始位置',
        keywords: [
            '返回开始',
            '重新开始',
            '回到起点',
            '从头开始'
        ]
    },
    {
        id: 'replay',
        name: '重播',
        description: '重新播放当前内容',
        keywords: [
            '再播一次',
            '重播',
            '再来一遍',
            '重新来',
            '重新播放'
        ]
    },
    {
        id: 'pause',
        name: '暂停',
        description: '暂停播放',
        keywords: [
            '暂停'
        ]
    },
    {
        id: 'resume',
        name: '继续',
        description: '继续播放',
        keywords: [
            '继续',
            '接着播放'
        ]
    },
    {
        id: 'volumeUp',
        name: '音量调大',
        description: '增大音量',
        keywords: [
            '调大音量',
            '音量调高',
            '声音大一点',
            '声音调大'
        ]
    },
    {
        id: 'volumeDown',
        name: '音量调小',
        description: '减小音量',
        keywords: [
            '调小音量',
            '音量调低',
            '声音小一点',
            '声音调小'
        ]
    }
]

/**
 * 创建命令映射配置
 */
export function createCommandMapping(): CommandMapping {
    return {
        commands: COMMAND_TYPES
    }
}

/**
 * 从映射配置中提取所有关键词
 */
export function extractKeywordsFromMapping(mapping: CommandMapping): string[] {
    const keywords: string[] = []
    for (const command of mapping.commands) {
        keywords.push(...command.keywords)
    }
    return keywords
}

/**
 * 获取所有关键词列表
 */
export function getAllKeywords(): string[] {
    return extractKeywordsFromMapping(createCommandMapping())
}

/**
 * 根据命令ID查找命令信息
 */
export function findCommandById(commandId: string): CommandType | undefined {
    return COMMAND_TYPES.find(cmd => cmd.id === commandId)
}

/**
 * 根据关键词查找对应的命令信息
 */
export function findCommandByKeyword(keyword: string): CommandType | undefined {
    return COMMAND_TYPES.find(cmd => cmd.keywords.includes(keyword))
}

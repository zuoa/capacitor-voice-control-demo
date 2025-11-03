import { registerPlugin } from '@capacitor/core'

/**
 * Sherpa-ONNX 插件接口定义
 * 基于 sherpa-onnx 的离线关键词识别引擎
 */
export interface SherpaOnnxPlugin {
  /**
   * 初始化 sherpa-onnx 引擎
   * @param options 配置选项
   *   - modelPath: 模型文件路径（可选，默认从assets加载）
   *   - keywords: 关键词列表
   *   - sampleRate: 采样率（默认16000）
   *   - numThreads: 线程数（默认1）
   *   - threshold: 检测阈值 0.0-1.0（默认0.5）
   */
  init(options?: {
    modelPath?: string
    keywords?: string[]
    sampleRate?: number
    numThreads?: number
    threshold?: number
  }): Promise<{ ok: boolean; message?: string }>

  /**
   * 开始识别
   */
  start(): Promise<{ ok: boolean }>

  /**
   * 停止识别
   */
  stop(): Promise<{ ok: boolean }>

  /**
   * 更新关键词列表
   * @param options 包含 keywords 数组
   */
  updateKeywords(options: { keywords: string[] }): Promise<{ ok: boolean }>

  /**
   * 设置检测阈值
   * @param options 包含 threshold (0.0-1.0)
   */
  setThreshold(options: { threshold: number }): Promise<{ ok: boolean }>

  /**
   * 获取识别状态
   */
  getStatus(): Promise<{
    isRunning: boolean
    isPaused: boolean
    keywordsCount: number
    threshold: number
    sampleRate: number
  }>

  /**
   * 获取支持的关键词列表
   */
  getKeywords(): Promise<{
    keywords: string[]
  }>

  /**
   * 枚举可用的音频输入设备
   */
  listInputs(): Promise<{
    inputs: Array<{
      stableId: string
      type: number
      label?: string
      address?: string
      id?: number
      isSource?: boolean
    }>
  }>

  /**
   * 选择指定的输入设备
   * @param options 包含 stableId
   */
  selectInput(options: { stableId: string }): Promise<{
    ok: boolean
    applied?: boolean
    deviceName?: string
    deviceType?: number
    deviceId?: number
  }>

  /**
   * 添加事件监听器
   * @param eventName 事件名称
   * @param listenerFunc 监听函数
   */
  addListener(
    eventName: 'onKeywordDetected' | 'onError' | 'onReady',
    listenerFunc: (data: any) => void
  ): { remove: () => void }
}

/**
 * 关键词检测事件数据
 */
export interface KeywordDetectedEvent {
  keyword: string
  confidence: number
  timestamp: number
}

/**
 * 错误事件数据
 */
export interface ErrorEvent {
  code: string
  message: string
}

// 注册插件
export const SherpaOnnx = registerPlugin<SherpaOnnxPlugin>('SherpaOnnx')


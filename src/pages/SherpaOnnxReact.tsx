import React, { useState, useEffect, useCallback, useRef } from 'react'
import { SherpaOnnx, KeywordDetectedEvent, ErrorEvent } from '../capacitor/plugins'
import { Platform } from '../capacitor/utils'
import { showToast } from '../components/common/Toast'
import { findCommandByKeyword } from '../services/commands'
import { Button } from '../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card'
import { Badge } from '../components/ui/badge'
import { Separator } from '../components/ui/separator'
import { ScrollArea } from '../components/ui/scroll-area'
import { Label } from '../components/ui/label'
import { Input } from '../components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from '../components/ui/select'

interface MicrophoneDevice {
  stableId: string
  type: number
  label?: string
  address?: string
  id?: number
  isSource?: boolean
}

interface DetectionResult {
  keyword: string
  confidence: number
  timestamp: number
  commandInfo?: {
    name: string
    description?: string
  }
}

// 视频源配置（移到组件外部，避免每次渲染重新创建）
const VIDEO_SOURCES = [
  'https://stream7.iqilu.com/10339/upload_transcode/202002/09/20200209105011F0zPoYzHry.mp4',
  'https://sf1-cdn-tos.huoshanstatic.com/obj/media-fe/xgplayer_doc_video/mp4/xgplayer-demo-360p.mp4',
  'https://stream7.iqilu.com/10339/upload_transcode/202002/09/20200209104902N3v5Vpxuvb.mp4'
]

export function SherpaOnnxPage() {
  // 状态管理
  const [isInitialized, setIsInitialized] = useState(false)
  const [isRunning, setIsRunning] = useState(false)
  const [keywords, setKeywords] = useState<string[]>([])
  const [keywordsLoading, setKeywordsLoading] = useState(true)
  const [microphones, setMicrophones] = useState<MicrophoneDevice[]>([])
  const [selectedMicId, setSelectedMicId] = useState<string>('')
  const [threshold, setThreshold] = useState(20)
  const [sampleRate, setSampleRate] = useState(44100)
  const [numThreads, setNumThreads] = useState(1)
  const [status, setStatus] = useState<{ message: string; type: 'info' | 'success' | 'error' }>({
    message: '',
    type: 'info'
  })
  const [logs, setLogs] = useState<string[]>([])
  const [detectionResult, setDetectionResult] = useState<DetectionResult | null>(null)

  // 引用事件监听器，用于清理
  const listenersRef = useRef<Array<{ remove: () => void }>>([])
  const statusIntervalRef = useRef<number | null>(null)

  // 视频引用
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const [currentVideoIndex, setCurrentVideoIndex] = useState<number>(0)

  // 日志记录
  const log = useCallback((message: string) => {
    const ts = new Date().toISOString()
    setLogs(prev => [`[${ts}] ${message}`, ...prev])
  }, [])

  const getCurrentVideo = useCallback((): HTMLVideoElement | null => {
    return videoRef.current
  }, [])

  const controlVideosByCommand = useCallback((commandId: string) => {
    const video = getCurrentVideo()
    if (!video) return

    const clamp = (val: number, min: number, max: number) => Math.min(Math.max(val, min), max)

    switch (commandId) {
      case 'resume': {
        const p = video.play()
        if (p && typeof p.catch === 'function') p.catch(() => {})
        log('执行: 继续播放')
        break
      }
      case 'pause': {
        video.pause()
        log('执行: 暂停')
        break
      }
      case 'replay': {
        video.currentTime = 0
        // 确保自动播放
        const playPromise = video.play()
        if (playPromise && typeof playPromise.catch === 'function') {
          playPromise.catch((err) => {
            // 如果自动播放失败，记录但不中断
            log('重播自动播放被阻止，可能需要用户交互')
          })
        }
        log('执行: 重播')
        break
      }
      case 'restart': {
        video.currentTime = 0
        video.pause()
        log('执行: 返回开始（回到0并暂停）')
        break
      }
      case 'previous': {
        // 使用函数式更新确保获取最新的 currentVideoIndex
        setCurrentVideoIndex((prevIndex) => {
          const newIndex = (prevIndex - 1 + VIDEO_SOURCES.length) % VIDEO_SOURCES.length
          log(`执行: 切换到上一个视频（视频 ${newIndex + 1}/${VIDEO_SOURCES.length}）`)
          return newIndex
        })
        break
      }
      case 'next': {
        // 使用函数式更新确保获取最新的 currentVideoIndex
        setCurrentVideoIndex((prevIndex) => {
          const newIndex = (prevIndex + 1) % VIDEO_SOURCES.length
          log(`执行: 切换到下一个视频（视频 ${newIndex + 1}/${VIDEO_SOURCES.length}）`)
          return newIndex
        })
        break
      }
      case 'volumeUp': {
        video.volume = clamp(video.volume + 0.1, 0, 1)
        log('执行: 音量调大')
        break
      }
      case 'volumeDown': {
        video.volume = clamp(video.volume - 0.1, 0, 1)
        log('执行: 音量调小')
        break
      }
      default:
        break
    }
  }, [getCurrentVideo, log])

  // 更新状态
  const updateStatus = useCallback((message: string, type: 'info' | 'success' | 'error' = 'info') => {
    setStatus({ message, type })
  }, [])

  // 加载关键词列表
  const loadKeywords = useCallback(async () => {
    setKeywordsLoading(true)
    try {
      const result = await SherpaOnnx.getKeywords()
      const keywordsList = result?.keywords || []
      setKeywords(keywordsList)
      if (keywordsList.length > 0) {
        log(`已加载 ${keywordsList.length} 个关键词`)
      } else {
        log('未找到关键词')
      }
    } catch (e: any) {
      log(`加载关键词失败: ${e?.message || e}`)
    } finally {
      setKeywordsLoading(false)
    }
  }, [log])

  // 加载麦克风设备列表
  const populateMicrophones = useCallback(async () => {
    try {
      if (Platform.isNative()) {
        const res = await SherpaOnnx.listInputs()
        const inputs = res?.inputs || []
        setMicrophones(inputs)

        if (!inputs.length) {
          log('未发现麦克风')
          setSelectedMicId('no-microphone')
          return
        }

        const preferredStableId = localStorage.getItem('sherpa-preferredMicStableId') || ''
        if (preferredStableId && inputs.some(d => d.stableId === preferredStableId)) {
          setSelectedMicId(preferredStableId)
        } else if (inputs[0]) {
          setSelectedMicId(inputs[0].stableId)
        }

        log(`已检测到麦克风: ${inputs.length} 个`)
      } else {
        log('Web 环境暂不支持设备选择')
        setSelectedMicId('web-default')
      }
    } catch (e: any) {
      log(`枚举设备失败: ${e?.message || e}`)
    }
  }, [log])

  // 选择麦克风
  const selectMicrophone = useCallback(async (stableId: string) => {
    if (!stableId) return
    
    // 跳过特殊值
    if (stableId === 'no-microphone' || stableId === 'web-default') {
      return
    }

    if (Platform.isNative()) {
      try {
        localStorage.setItem('sherpa-preferredMicStableId', stableId)
        const res = await SherpaOnnx.selectInput({ stableId })
        if (res?.applied) {
          log(`✓ 已选择麦克风: ${res.deviceName || stableId}`)
        } else {
          log(`⚠️ 已选择麦克风: ${stableId}`)
        }
      } catch (e: any) {
        log(`选择麦克风失败: ${e?.message || e}`)
      }
    }
  }, [log])

  // 初始化
  const handleInit = useCallback(async () => {
    log('初始化中...')
    updateStatus('正在初始化...', 'info')

    try {
      log(`配置: 使用内置关键词, 采样率=${sampleRate}, 线程数=${numThreads}, 阈值=${(threshold / 100).toFixed(2)}`)

      const result = await SherpaOnnx.init({
        keywords: [], // 使用模型内置的keywords.txt
        sampleRate,
        numThreads,
        threshold: threshold / 100
      })

      if (result.ok) {
        setIsInitialized(true)
        log('✓ 初始化成功')
        updateStatus('初始化成功', 'success')
      } else {
        log(`✗ 初始化失败: ${result.message || '未知错误'}`)
        updateStatus('初始化失败', 'error')
      }
    } catch (e: any) {
      log(`✗ 初始化异常: ${e?.message || e}`)
      updateStatus('初始化异常', 'error')
    }
  }, [sampleRate, numThreads, threshold, log, updateStatus])

  // 开始识别
  const handleStart = useCallback(async () => {
    if (!isInitialized) {
      log('⚠️ 请先初始化')
      updateStatus('请先初始化', 'error')
      return
    }

    log('开始识别...')
    updateStatus('识别中...', 'info')

    try {
      const result = await SherpaOnnx.start()
      if (result.ok) {
        setIsRunning(true)
        log('✓ 识别已开始')
        updateStatus('识别中...', 'success')
      } else {
        log('✗ 启动失败')
        updateStatus('启动失败', 'error')
      }
    } catch (e: any) {
      log(`✗ 启动异常: ${e?.message || e}`)
      updateStatus('启动异常', 'error')
    }
  }, [isInitialized, log, updateStatus])

  // 停止识别
  const handleStop = useCallback(async () => {
    if (!isRunning) {
      log('⚠️ 未在识别中')
      return
    }

    log('停止识别...')

    try {
      await SherpaOnnx.stop()
      setIsRunning(false)
      log('✓ 识别已停止')
      updateStatus('已停止', 'info')
    } catch (e: any) {
      log(`✗ 停止异常: ${e?.message || e}`)
    }
  }, [isRunning, log, updateStatus])

  // 更新关键词（这个功能在原始代码中存在，但UI中没有输入框，暂时保留逻辑）
  const handleUpdateKeywords = useCallback(async () => {
    if (!isInitialized) {
      log('⚠️ 请先初始化')
      updateStatus('请先初始化', 'error')
      return
    }

    // 注意：原始代码中尝试从不存在的 textarea 获取关键词，这里暂时跳过
    log('⚠️ 更新关键词功能需要先添加关键词输入框')
    updateStatus('请先添加关键词输入', 'error')
  }, [isInitialized, log, updateStatus])

  // 刷新状态
  const refreshStatus = useCallback(async () => {
    try {
      const status = await SherpaOnnx.getStatus()
      log(`状态: 运行中=${status.isRunning}, 暂停=${status.isPaused}, 关键词数=${status.keywordsCount}, 阈值=${status.threshold.toFixed(2)}`)
    } catch (e: any) {
      // 忽略错误
    }
  }, [log])

  // 设置事件监听器
  useEffect(() => {
    // 关键词检测监听器
    const keywordListener = SherpaOnnx.addListener('onKeywordDetected', (event: KeywordDetectedEvent) => {
      const commandInfo = findCommandByKeyword(event.keyword)
      const commandName = commandInfo?.name || '未知命令'
      
      log(`🎯 检测到关键词: ${event.keyword} (命令类别: ${commandName}, 置信度: ${(event.confidence * 100).toFixed(1)}%)`)

      const toastMessage = commandInfo ? `${commandInfo.name}: ${event.keyword}` : event.keyword
      showToast({
        message: toastMessage,
        type: 'success',
        duration: 2000
      })

      setDetectionResult({
        keyword: event.keyword,
        confidence: event.confidence,
        timestamp: event.timestamp,
        commandInfo: commandInfo ? {
          name: commandInfo.name,
          description: commandInfo.description
        } : undefined
      })

      const statusMessage = commandInfo
        ? `检测到: ${commandInfo.name} - ${event.keyword}`
        : `检测到: ${event.keyword}`
      updateStatus(statusMessage, 'success')

      // 执行视频控制
      if (commandInfo?.id) {
        controlVideosByCommand(commandInfo.id)
      }
    })

    // 错误监听器
    const errorListener = SherpaOnnx.addListener('onError', (error: ErrorEvent) => {
      log(`❌ 错误: ${error.code} - ${error.message}`)
      updateStatus(`错误: ${error.message}`, 'error')
    })

    // 就绪监听器
    const readyListener = SherpaOnnx.addListener('onReady', () => {
      log('✓ Sherpa-ONNX 已就绪')
      updateStatus('已就绪，可以开始识别', 'success')
    })

    listenersRef.current = [keywordListener, errorListener, readyListener]

    // 初始化时加载数据
    loadKeywords()
    populateMicrophones()

    // 定期刷新状态
    statusIntervalRef.current = window.setInterval(refreshStatus, 5000)

    // 清理函数
    return () => {
      listenersRef.current.forEach(listener => listener.remove())
      if (statusIntervalRef.current !== null) {
        clearInterval(statusIntervalRef.current)
      }
    }
  }, [loadKeywords, populateMicrophones, refreshStatus, log, updateStatus])

  // 麦克风选择变化
  useEffect(() => {
    if (selectedMicId) {
      selectMicrophone(selectedMicId)
    }
  }, [selectedMicId, selectMicrophone])

  // 视频切换时自动播放
  useEffect(() => {
    // 使用 setTimeout 确保 DOM 更新完成
    const timer = setTimeout(() => {
      const video = videoRef.current
      if (!video) return

      // 当视频可以播放时，自动播放
      const handleCanPlay = () => {
        const p = video.play()
        if (p && typeof p.catch === 'function') {
          p.catch(() => {
            // 如果自动播放失败（可能是浏览器策略），记录日志但不报错
            log('视频自动播放被阻止（可能需要用户交互）')
          })
        }
      }

      // 如果视频已经加载好，直接播放
      if (video.readyState >= 3) {
        handleCanPlay()
      } else {
        video.addEventListener('canplay', handleCanPlay, { once: true })
        video.addEventListener('loadeddata', handleCanPlay, { once: true })
      }
    }, 100)

    return () => {
      clearTimeout(timer)
    }
  }, [currentVideoIndex, log])

  // 处理阈值变化
  const handleThresholdChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setThreshold(parseInt(e.target.value))
  }, [])

  return (
    <div className="sherpa-onnx-page min-h-screen p-4 space-y-4 bg-background">
      <Card>
        <CardHeader>
          <CardTitle>Sherpa-ONNX 指令识别测试</CardTitle>
          <CardDescription>
            基于 sherpa-onnx 的离线关键词识别引擎
          </CardDescription>
          <div className="text-sm text-muted-foreground mt-2">
            ✓ 完全离线 ✓ 无需网络 ✓ 低延迟
          </div>
        </CardHeader>
      </Card>

      {/* 测试视频区域 */}
      <Card>
        <CardHeader>
          <CardTitle>测试视频（支持语音指令控制）</CardTitle>
          <CardDescription>
            指令：继续、暂停、重播、返回开始、上一步/下一步（切换视频）、音量调大/音量调小
            <br />
            当前视频：{currentVideoIndex + 1} / {VIDEO_SOURCES.length}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="w-full">
            <video
              key={currentVideoIndex}
              ref={videoRef}
              controls
              className="w-full rounded border max-w-4xl mx-auto"
              src={VIDEO_SOURCES[currentVideoIndex]}
            />
          </div>
        </CardContent>
      </Card>

      {/* 支持的关键词显示 */}
      <Card>
        <CardHeader>
          <CardTitle>支持的关键词</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {keywordsLoading ? (
            <p className="text-muted-foreground">加载中...</p>
          ) : keywords.length === 0 ? (
            <p className="text-muted-foreground">未找到关键词</p>
          ) : (
            <div className="flex flex-wrap gap-2">
              {keywords.map(kw => (
                <Badge key={kw} variant="secondary">
                  {kw}
                </Badge>
              ))}
            </div>
          )}
          <Button variant="outline" size="sm" onClick={loadKeywords}>
            刷新关键词
          </Button>
        </CardContent>
      </Card>

      {/* 麦克风选择 */}
      <Card>
        <CardHeader>
          <CardTitle>选择麦克风</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex gap-2">
            <Select value={selectedMicId} onValueChange={setSelectedMicId}>
              <SelectTrigger className="flex-1">
                <SelectValue placeholder="选择麦克风" />
              </SelectTrigger>
              <SelectContent>
                {Platform.isNative() ? (
                  microphones.length === 0 ? (
                    <SelectItem value="no-microphone" disabled>未发现麦克风</SelectItem>
                  ) : (
                    microphones.map(device => (
                      <SelectItem key={device.stableId} value={device.stableId}>
                        {device.label || `设备 ${device.type} (ID: ${device.id})`}
                      </SelectItem>
                    ))
                  )
                ) : (
                  <SelectItem value="web-default">Web环境：使用默认麦克风</SelectItem>
                )}
              </SelectContent>
            </Select>
            <Button variant="outline" onClick={populateMicrophones}>
              刷新设备
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* 配置区域 */}
      <Card>
        <CardHeader>
          <CardTitle>配置</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="sherpa-threshold">
              检测阈值：{(threshold / 100).toFixed(2)}
            </Label>
            <Input
              type="range"
              id="sherpa-threshold"
              min="0"
              max="100"
              value={threshold}
              onChange={handleThresholdChange}
              className="w-full"
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="sherpa-sample-rate">采样率</Label>
            <Select value={sampleRate.toString()} onValueChange={(value) => setSampleRate(parseInt(value))}>
              <SelectTrigger id="sherpa-sample-rate">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="16000">16000 Hz</SelectItem>
                <SelectItem value="8000">8000 Hz</SelectItem>
                <SelectItem value="44100">44100 Hz</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="sherpa-num-threads">线程数</Label>
            <Select value={numThreads.toString()} onValueChange={(value) => setNumThreads(parseInt(value))}>
              <SelectTrigger id="sherpa-num-threads">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="1">1</SelectItem>
                <SelectItem value="2">2</SelectItem>
                <SelectItem value="4">4</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </CardContent>
      </Card>

      {/* 控制按钮 */}
      <Card>
        <CardContent className="pt-6">
          <div className="flex flex-wrap gap-2">
            <Button onClick={handleInit}>初始化</Button>
            <Button 
              onClick={handleStart} 
              disabled={!isInitialized || isRunning}
              variant="default"
            >
              开始识别
            </Button>
            <Button 
              onClick={handleStop} 
              disabled={!isRunning}
              variant="destructive"
            >
              停止
            </Button>
            <Button 
              onClick={handleUpdateKeywords} 
              disabled={!isInitialized}
              variant="outline"
            >
              更新关键词
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* 状态显示 */}
      {status.message && (
        <Card className={status.type === 'error' ? 'border-destructive' : status.type === 'success' ? 'border-green-500' : ''}>
          <CardContent className="pt-6">
            <div className={`text-sm ${status.type === 'error' ? 'text-destructive' : status.type === 'success' ? 'text-green-600' : 'text-muted-foreground'}`}>
              {status.message}
            </div>
          </CardContent>
        </Card>
      )}

      {/* 日志 */}
      <Card>
        <CardHeader>
          <CardTitle>日志</CardTitle>
        </CardHeader>
        <CardContent>
          <ScrollArea className="h-[200px] w-full rounded border p-4">
            <pre className="text-xs font-mono">
              {logs.length === 0 ? '' : logs.join('\n')}
            </pre>
          </ScrollArea>
        </CardContent>
      </Card>

      {/* 检测结果 */}
      <Card>
        <CardHeader>
          <CardTitle>检测结果</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {detectionResult ? (
            <>
              {detectionResult.commandInfo ? (
                <>
                  <div>
                    <div className="text-sm text-muted-foreground mb-1">
                      命令类别
                    </div>
                    <Badge className="text-base px-3 py-1" variant="default">
                      {detectionResult.commandInfo.name}
                    </Badge>
                  </div>
                  <div>
                    <div className="text-sm text-muted-foreground mb-1">
                      具体指令
                    </div>
                    <div className="text-2xl font-bold text-green-600">
                      {detectionResult.keyword}
                    </div>
                  </div>
                  {detectionResult.commandInfo.description && (
                    <div>
                      <div className="text-sm text-muted-foreground mb-1">
                        说明
                      </div>
                      <div className="text-sm text-muted-foreground">
                        {detectionResult.commandInfo.description}
                      </div>
                    </div>
                  )}
                  <Separator />
                  <div className="text-xs text-muted-foreground">
                    置信度: {(detectionResult.confidence * 100).toFixed(1)}% |
                    时间: {new Date(detectionResult.timestamp).toLocaleTimeString()}
                  </div>
                </>
              ) : (
                <>
                  <div className="text-2xl font-bold text-green-600">
                    {detectionResult.keyword}
                  </div>
                  <div className="text-sm text-yellow-600">
                    ⚠️ 未找到对应的命令类别
                  </div>
                  <div className="text-xs text-muted-foreground">
                    置信度: {(detectionResult.confidence * 100).toFixed(1)}% |
                    时间: {new Date(detectionResult.timestamp).toLocaleTimeString()}
                  </div>
                </>
              )}
            </>
          ) : (
            <div className="text-muted-foreground">暂无检测结果</div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

import React, { useState, useEffect, useCallback, useRef } from 'react'
import { SherpaOnnx, KeywordDetectedEvent, ErrorEvent } from '../capacitor/plugins'
import { Platform } from '../capacitor/utils'
import { showToast } from '../components/common/Toast'
import { findCommandByKeyword } from '../services/commands'

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

  // 日志记录
  const log = useCallback((message: string) => {
    const ts = new Date().toISOString()
    setLogs(prev => [`[${ts}] ${message}`, ...prev])
  }, [])

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
      }
    } catch (e: any) {
      log(`枚举设备失败: ${e?.message || e}`)
    }
  }, [log])

  // 选择麦克风
  const selectMicrophone = useCallback(async (stableId: string) => {
    if (!stableId) return

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

  // 处理阈值变化
  const handleThresholdChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setThreshold(parseInt(e.target.value))
  }, [])

  // 处理采样率变化
  const handleSampleRateChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
    setSampleRate(parseInt(e.target.value))
  }, [])

  // 处理线程数变化
  const handleNumThreadsChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
    setNumThreads(parseInt(e.target.value))
  }, [])

  // 处理麦克风选择变化
  const handleMicSelectChange = useCallback((e: React.ChangeEvent<HTMLSelectElement>) => {
    setSelectedMicId(e.target.value)
  }, [])

  return (
    <div className="sherpa-onnx-page">
      <h2>Sherpa-ONNX 指令识别测试</h2>

      <div className="info-box">
        <p>基于 sherpa-onnx 的离线关键词识别引擎</p>
        <p style={{ fontSize: '0.9em', color: '#888' }}>
          ✓ 完全离线 ✓ 无需网络 ✓ 低延迟
        </p>
      </div>

      {/* 支持的关键词显示 */}
      <div className="keywords-section">
        <h3>支持的关键词</h3>
        <div className="keywords-list">
          {keywordsLoading ? (
            <p style={{ color: '#888' }}>加载中...</p>
          ) : keywords.length === 0 ? (
            <p style={{ color: '#888' }}>未找到关键词</p>
          ) : (
            keywords.map(kw => (
              <span key={kw} className="keyword-tag">
                {kw}
              </span>
            ))
          )}
        </div>
        <button
          onClick={loadKeywords}
          style={{ marginTop: '8px' }}
        >
          刷新关键词
        </button>
      </div>

      {/* 麦克风选择 */}
      <div className="mic-select">
        <h3>选择麦克风</h3>
        <div className="config-row">
          <select
            value={selectedMicId}
            onChange={handleMicSelectChange}
          >
            {Platform.isNative() ? (
              microphones.length === 0 ? (
                <option value="">未发现麦克风</option>
              ) : (
                microphones.map(device => (
                  <option key={device.stableId} value={device.stableId}>
                    {device.label || `设备 ${device.type} (ID: ${device.id})`}
                  </option>
                ))
              )
            ) : (
              <option value="">Web环境：使用默认麦克风</option>
            )}
          </select>
          <button onClick={populateMicrophones}>刷新设备</button>
        </div>
      </div>

      {/* 配置区域 */}
      <div className="config-section">
        <h3>配置</h3>

        <div className="config-row">
          <label>检测阈值：</label>
          <input
            type="range"
            id="sherpa-threshold"
            min="0"
            max="100"
            value={threshold}
            onChange={handleThresholdChange}
          />
          <span>{(threshold / 100).toFixed(2)}</span>
        </div>

        <div className="config-row">
          <label>采样率：</label>
          <select
            id="sherpa-sample-rate"
            value={sampleRate}
            onChange={handleSampleRateChange}
          >
            <option value={16000}>16000 Hz</option>
            <option value={8000}>8000 Hz</option>
            <option value={44100}>44100 Hz</option>
          </select>
        </div>

        <div className="config-row">
          <label>线程数：</label>
          <select
            id="sherpa-num-threads"
            value={numThreads}
            onChange={handleNumThreadsChange}
          >
            <option value={1}>1</option>
            <option value={2}>2</option>
            <option value={4}>4</option>
          </select>
        </div>
      </div>

      {/* 控制按钮 */}
      <div className="controls">
        <button onClick={handleInit}>初始化</button>
        <button onClick={handleStart} disabled={!isInitialized || isRunning}>
          开始识别
        </button>
        <button onClick={handleStop} disabled={!isRunning}>
          停止
        </button>
        <button onClick={handleUpdateKeywords} disabled={!isInitialized}>
          更新关键词
        </button>
      </div>

      {/* 状态显示 */}
      {status.message && (
        <div className={`status-box ${status.type}`}>
          {status.message}
        </div>
      )}

      {/* 日志 */}
      <pre className="log">
        {logs.length === 0 ? '' : logs.join('\n')}
      </pre>

      {/* 检测结果 */}
      <div className="result">
        <h3>检测结果</h3>
        <div className="result-text">
          {detectionResult ? (
            <>
              {detectionResult.commandInfo ? (
                <>
                  <div style={{ marginBottom: '12px' }}>
                    <div style={{ fontSize: '0.9em', color: '#666', marginBottom: '4px' }}>
                      命令类别
                    </div>
                    <div style={{ fontSize: '1.1em', color: '#3b82f6', fontWeight: 'bold' }}>
                      {detectionResult.commandInfo.name}
                    </div>
                  </div>
                  <div style={{ marginBottom: '12px' }}>
                    <div style={{ fontSize: '0.9em', color: '#666', marginBottom: '4px' }}>
                      具体指令
                    </div>
                    <div style={{ fontSize: '1.2em', color: '#10b981', fontWeight: 'bold' }}>
                      {detectionResult.keyword}
                    </div>
                  </div>
                  {detectionResult.commandInfo.description && (
                    <div style={{ marginBottom: '12px' }}>
                      <div style={{ fontSize: '0.9em', color: '#666', marginBottom: '4px' }}>
                        说明
                      </div>
                      <div style={{ fontSize: '0.95em', color: '#888' }}>
                        {detectionResult.commandInfo.description}
                      </div>
                    </div>
                  )}
                  <div
                    style={{
                      marginTop: '12px',
                      paddingTop: '12px',
                      borderTop: '1px solid #e5e7eb',
                      color: '#888',
                      fontSize: '0.85em'
                    }}
                  >
                    置信度: {(detectionResult.confidence * 100).toFixed(1)}% |
                    时间: {new Date(detectionResult.timestamp).toLocaleTimeString()}
                  </div>
                </>
              ) : (
                <>
                  <div style={{ fontSize: '1.2em', color: '#10b981', fontWeight: 'bold' }}>
                    {detectionResult.keyword}
                  </div>
                  <div style={{ marginTop: '8px', color: '#f59e0b', fontSize: '0.9em' }}>
                    ⚠️ 未找到对应的命令类别
                  </div>
                  <div style={{ marginTop: '8px', color: '#888', fontSize: '0.85em' }}>
                    置信度: {(detectionResult.confidence * 100).toFixed(1)}% |
                    时间: {new Date(detectionResult.timestamp).toLocaleTimeString()}
                  </div>
                </>
              )}
            </>
          ) : (
            <div style={{ color: '#888' }}>暂无检测结果</div>
          )}
        </div>
      </div>
    </div>
  )
}

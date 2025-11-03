/**
 * Sherpa-ONNX 测试页面
 */
import { SherpaOnnx, KeywordDetectedEvent, ErrorEvent } from '../capacitor/plugins'
import { Platform } from '../capacitor/utils'

export function createSherpaOnnxPage(): string {
  return `
    <div class="sherpa-onnx-page">
      <h2>Sherpa-ONNX 指令识别测试</h2>
      
      <div class="info-box">
        <p>基于 sherpa-onnx 的离线关键词识别引擎</p>
        <p style="font-size: 0.9em; color: #888;">✓ 完全离线 ✓ 无需网络 ✓ 低延迟</p>
      </div>
      
      <!-- 支持的关键词显示 -->
      <div class="keywords-section">
        <h3>支持的关键词</h3>
        <div id="sherpa-keywords-list" class="keywords-list">
          <p style="color: #888;">加载中...</p>
        </div>
        <button id="sherpa-btn-refresh-keywords" style="margin-top: 8px;">刷新关键词</button>
      </div>
      
      <!-- 麦克风选择 -->
      <div class="mic-select">
        <h3>选择麦克风</h3>
        <div class="config-row">
          <select id="sherpa-mic-select"></select>
          <button id="sherpa-btn-refresh-mics">刷新设备</button>
        </div>
      </div>
      
      <!-- 配置区域 -->
      <div class="config-section">
        <h3>配置</h3>
        
        <div class="config-row">
          <label>检测阈值：</label>
          <input type="range" id="sherpa-threshold" min="0" max="100" value="20" />
          <span id="sherpa-threshold-value">0.20</span>
        </div>
        
        <div class="config-row">
          <label>采样率：</label>
          <select id="sherpa-sample-rate">
            <option value="16000">16000 Hz</option>
            <option value="8000">8000 Hz</option>
            <option value="44100" selected>44100 Hz</option>
          </select>
        </div>
        
        <div class="config-row">
          <label>线程数：</label>
          <select id="sherpa-num-threads">
            <option value="1" selected>1</option>
            <option value="2">2</option>
            <option value="4">4</option>
          </select>
        </div>
      </div>
      
      <!-- 控制按钮 -->
      <div class="controls">
        <button id="sherpa-btn-init">初始化</button>
        <button id="sherpa-btn-start">开始识别</button>
        <button id="sherpa-btn-stop">停止</button>
        <button id="sherpa-btn-update-keywords">更新关键词</button>
      </div>
      
      <!-- 状态显示 -->
      <div id="sherpa-status" class="status-box"></div>
      
      <!-- 日志 -->
      <pre id="sherpa-log" class="log"></pre>
      
      <!-- 检测结果 -->
      <div class="result">
        <h3>检测结果</h3>
        <div id="sherpa-result-text" class="result-text"></div>
      </div>
    </div>
  `
}

export function setupSherpaOnnxPage() {
  let isInitialized = false
  let isRunning = false
  
  function log(message: string) {
    const el = document.getElementById('sherpa-log')!
    const ts = new Date().toISOString()
    el.textContent = `[${ts}] ${message}\n` + el.textContent
  }
  
  function updateStatus(message: string, type: 'info' | 'success' | 'error' = 'info') {
    const statusEl = document.getElementById('sherpa-status')!
    statusEl.textContent = message
    statusEl.className = `status-box ${type}`
  }
  
  // 麦克风设备管理
  async function populateMicrophones() {
    const select = document.getElementById('sherpa-mic-select') as HTMLSelectElement
    if (!select) return
    
    try {
      if (Platform.isNative()) {
        // 使用原生 API 获取设备列表
        const res = await SherpaOnnx.listInputs()
        const inputs = res?.inputs || []
        select.innerHTML = ''
        
        if (!inputs.length) {
          select.innerHTML = `<option value="">未发现麦克风</option>`
          return
        }
        
        const preferredStableId = localStorage.getItem('sherpa-preferredMicStableId') || ''
        
        for (const d of inputs) {
          const opt = document.createElement('option')
          opt.value = d.stableId
          opt.textContent = d.label || `设备 ${d.type} (ID: ${d.id})`
          if (preferredStableId && preferredStableId === d.stableId) {
            opt.selected = true
          }
          select.appendChild(opt)
        }
        
        if (!select.value && inputs[0]) select.value = inputs[0].stableId
        log(`已检测到麦克风: ${inputs.length} 个`)
      } else {
        // Web 环境
        select.innerHTML = `<option value="">Web环境：使用默认麦克风</option>`
        log('Web 环境暂不支持设备选择')
      }
    } catch (e: any) {
      log(`枚举设备失败: ${e?.message || e}`)
      select.innerHTML = `<option value="">获取设备失败</option>`
    }
  }
  
  // 选择麦克风
  async function selectMicrophone(stableId: string) {
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
  }
  
  // 监听关键词检测
  SherpaOnnx.addListener('onKeywordDetected', (event: KeywordDetectedEvent) => {
    log(`🎯 检测到关键词: ${event.keyword} (置信度: ${(event.confidence * 100).toFixed(1)}%)`)
    const resultEl = document.getElementById('sherpa-result-text')!
    resultEl.innerHTML = `
      <div style="font-size: 1.2em; color: #10b981; font-weight: bold;">
        ${event.keyword}
      </div>
      <div style="margin-top: 8px; color: #888;">
        置信度: ${(event.confidence * 100).toFixed(1)}% | 
        时间: ${new Date(event.timestamp).toLocaleTimeString()}
      </div>
    `
    updateStatus(`检测到: ${event.keyword}`, 'success')
  })
  
  // 监听错误
  SherpaOnnx.addListener('onError', (error: ErrorEvent) => {
    log(`❌ 错误: ${error.code} - ${error.message}`)
    updateStatus(`错误: ${error.message}`, 'error')
  })
  
  // 监听就绪
  SherpaOnnx.addListener('onReady', () => {
    log('✓ Sherpa-ONNX 已就绪')
    updateStatus('已就绪，可以开始识别', 'success')
  })
  
  // 阈值滑块
  const thresholdSlider = document.getElementById('sherpa-threshold') as HTMLInputElement
  const thresholdValue = document.getElementById('sherpa-threshold-value')!
  thresholdSlider.addEventListener('input', () => {
    const value = (parseFloat(thresholdSlider.value) / 100).toFixed(2)
    thresholdValue.textContent = value
  })
  
  // 初始化按钮
  document.getElementById('sherpa-btn-init')!.addEventListener('click', async () => {
    log('初始化中...')
    updateStatus('正在初始化...', 'info')
    
    try {
      // 使用模型内置的关键词，不需要传递keywords参数
      const sampleRate = parseInt((document.getElementById('sherpa-sample-rate') as HTMLSelectElement).value)
      const numThreads = parseInt((document.getElementById('sherpa-num-threads') as HTMLSelectElement).value)
      const threshold = parseFloat(thresholdSlider.value) / 100
      
      log(`配置: 使用内置关键词, 采样率=${sampleRate}, 线程数=${numThreads}, 阈值=${threshold.toFixed(2)}`)
      
      const result = await SherpaOnnx.init({
        keywords: [], // 使用模型内置的keywords.txt
        sampleRate,
        numThreads,
        threshold
      })
      
      if (result.ok) {
        isInitialized = true
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
  })
  
  // 开始识别按钮
  document.getElementById('sherpa-btn-start')!.addEventListener('click', async () => {
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
        isRunning = true
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
  })
  
  // 停止识别按钮
  document.getElementById('sherpa-btn-stop')!.addEventListener('click', async () => {
    if (!isRunning) {
      log('⚠️ 未在识别中')
      return
    }
    
    log('停止识别...')
    
    try {
      await SherpaOnnx.stop()
      isRunning = false
      log('✓ 识别已停止')
      updateStatus('已停止', 'info')
    } catch (e: any) {
      log(`✗ 停止异常: ${e?.message || e}`)
    }
  })
  
  // 更新关键词按钮
  document.getElementById('sherpa-btn-update-keywords')!.addEventListener('click', async () => {
    if (!isInitialized) {
      log('⚠️ 请先初始化')
      updateStatus('请先初始化', 'error')
      return
    }
    
    const keywordsText = (document.getElementById('sherpa-keywords-text') as HTMLTextAreaElement)?.value.trim()
    const keywords = keywordsText ? keywordsText.split('\n').filter(k => k.trim()) : []
    
    if (keywords.length === 0) {
      log('⚠️ 关键词列表为空')
      updateStatus('关键词列表为空', 'error')
      return
    }
    
    log(`更新关键词: ${keywords.length} 个`)
    
    try {
      const result = await SherpaOnnx.updateKeywords({ keywords })
      if (result.ok) {
        log(`✓ 关键词已更新`)
        updateStatus('关键词已更新', 'success')
      } else {
        log('✗ 更新失败')
        updateStatus('更新失败', 'error')
      }
    } catch (e: any) {
      log(`✗ 更新异常: ${e?.message || e}`)
      updateStatus('更新异常', 'error')
    }
  })
  
  // 获取状态
  async function refreshStatus() {
    try {
      const status = await SherpaOnnx.getStatus()
      log(`状态: 运行中=${status.isRunning}, 暂停=${status.isPaused}, 关键词数=${status.keywordsCount}, 阈值=${status.threshold.toFixed(2)}`)
    } catch (e: any) {
      // 忽略错误
    }
  }
  
  // 定期刷新状态
  setInterval(refreshStatus, 5000)
  
  // 加载并显示关键词
  async function loadKeywords() {
    const keywordsListEl = document.getElementById('sherpa-keywords-list')!
    
    try {
      const result = await SherpaOnnx.getKeywords()
      const keywords = result?.keywords || []
      
      if (keywords.length === 0) {
        keywordsListEl.innerHTML = '<p style="color: #888;">未找到关键词</p>'
        return
      }
      
      // 将关键词显示为标签样式
      keywordsListEl.innerHTML = keywords
        .map(kw => `<span class="keyword-tag">${kw}</span>`)
        .join('')
      
      log(`已加载 ${keywords.length} 个关键词`)
    } catch (e: any) {
      keywordsListEl.innerHTML = `<p style="color: #e74c3c;">加载失败: ${e?.message || e}</p>`
      log(`加载关键词失败: ${e?.message || e}`)
    }
  }
  
  // 初始化时加载关键词和麦克风列表
  loadKeywords().catch((e) => {
    log(`加载关键词列表失败: ${e}`)
  })
  
  populateMicrophones().catch((e) => {
    log(`加载麦克风列表失败: ${e}`)
  })
  
  // 麦克风选择变化
  const micSelect = document.getElementById('sherpa-mic-select') as HTMLSelectElement
  if (micSelect) {
    micSelect.addEventListener('change', () => {
      selectMicrophone(micSelect.value)
    })
  }
  
  // 刷新设备按钮
  const refreshBtn = document.getElementById('sherpa-btn-refresh-mics')
  if (refreshBtn) {
    refreshBtn.addEventListener('click', async () => {
      log('刷新麦克风列表...')
      await populateMicrophones()
    })
  }
  
  // 刷新关键词按钮
  const refreshKeywordsBtn = document.getElementById('sherpa-btn-refresh-keywords')
  if (refreshKeywordsBtn) {
    refreshKeywordsBtn.addEventListener('click', async () => {
      log('刷新关键词列表...')
      await loadKeywords()
    })
  }
}


import './style.css'
import { createSherpaOnnxPage, setupSherpaOnnxPage } from './sherpa-onnx-page'

const root = document.getElementById('app')!

// 直接加载 SherpaOnnx 页面
root.innerHTML = `<div id="page-content"></div>`

const pageContent = document.getElementById('page-content')!

// 加载 Sherpa-ONNX 页面
function loadSherpaOnnxPage() {
  pageContent.innerHTML = createSherpaOnnxPage()
  setupSherpaOnnxPage()
}

// 初始加载
loadSherpaOnnxPage()

/**
 * Toast 提示组件
 */

export interface ToastOptions {
  message: string
  duration?: number
  type?: 'success' | 'error' | 'info' | 'warning'
}

function getTypeColor(type: string): string {
  switch (type) {
    case 'success':
      return '#10b981'
    case 'error':
      return '#ef4444'
    case 'warning':
      return '#f59e0b'
    case 'info':
      return '#3b82f6'
    default:
      return 'rgba(255, 255, 255, 0.3)'
  }
}

export function showToast(options: ToastOptions) {
  const { message, duration = 2000, type = 'info' } = options
  
  // 创建 toast 容器（如果不存在）
  let toastContainer = document.getElementById('toast-container')
  if (!toastContainer) {
    toastContainer = document.createElement('div')
    toastContainer.id = 'toast-container'
    toastContainer.style.cssText = `
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      pointer-events: none;
      z-index: 10000;
      display: flex;
      align-items: center;
      justify-content: center;
    `
    document.body.appendChild(toastContainer)
  }
  
  // 创建 toast 元素
  const toast = document.createElement('div')
  toast.className = `toast toast-${type}`
  toast.style.cssText = `
    background: rgba(0, 0, 0, 0.85);
    color: white;
    padding: 24px 48px;
    border-radius: 16px;
    font-size: 2em;
    font-weight: bold;
    text-align: center;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
    animation: toast-show 0.3s ease-out;
    pointer-events: auto;
    backdrop-filter: blur(10px);
    border: 2px solid ${getTypeColor(type)};
  `
  toast.textContent = message
  
  // 添加到容器
  toastContainer.appendChild(toast)
  
  // 添加样式（如果还没有添加）
  if (!document.getElementById('toast-styles')) {
    const style = document.createElement('style')
    style.id = 'toast-styles'
    style.textContent = `
      @keyframes toast-show {
        from {
          opacity: 0;
          transform: scale(0.8) translateY(-20px);
        }
        to {
          opacity: 1;
          transform: scale(1) translateY(0);
        }
      }
      
      @keyframes toast-hide {
        from {
          opacity: 1;
          transform: scale(1) translateY(0);
        }
        to {
          opacity: 0;
          transform: scale(0.8) translateY(-20px);
        }
      }
      
      .toast-success {
        border-color: #10b981 !important;
        color: #10b981;
      }
      
      .toast-error {
        border-color: #ef4444 !important;
        color: #ef4444;
      }
      
      .toast-warning {
        border-color: #f59e0b !important;
        color: #f59e0b;
      }
      
      .toast-info {
        border-color: #3b82f6 !important;
        color: #3b82f6;
      }
    `
    document.head.appendChild(style)
  }
  
  // 自动移除
  setTimeout(() => {
    toast.style.animation = 'toast-hide 0.3s ease-in'
    setTimeout(() => {
      toast.remove()
    }, 300)
  }, duration)
}


/**
 * 权限管理工具
 */
export class PermissionManager {
  /**
   * 请求麦克风权限
   */
  static async requestMicrophonePermission(): Promise<boolean> {
    try {
      if (typeof navigator !== 'undefined' && navigator.mediaDevices) {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
        stream.getTracks().forEach(track => track.stop())
        return true
      }
      return false
    } catch (error) {
      console.error('请求麦克风权限失败:', error)
      return false
    }
  }

  /**
   * 检查麦克风权限
   */
  static async checkMicrophonePermission(): Promise<boolean> {
    try {
      if (typeof navigator !== 'undefined' && navigator.permissions) {
        const result = await navigator.permissions.query({ name: 'microphone' as PermissionName })
        return result.state === 'granted'
      }
      return false
    } catch (error) {
      // 某些浏览器可能不支持 permissions API
      return false
    }
  }
}


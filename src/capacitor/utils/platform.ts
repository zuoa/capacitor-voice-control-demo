import { Capacitor } from '@capacitor/core'

/**
 * 平台检测工具
 */
export class Platform {
  /**
   * 是否是原生平台
   */
  static isNative(): boolean {
    return Capacitor.isNativePlatform()
  }

  /**
   * 是否是 Android
   */
  static isAndroid(): boolean {
    return Capacitor.getPlatform() === 'android'
  }

  /**
   * 是否是 iOS
   */
  static isIOS(): boolean {
    return Capacitor.getPlatform() === 'ios'
  }

  /**
   * 是否是 Web
   */
  static isWeb(): boolean {
    return Capacitor.getPlatform() === 'web'
  }

  /**
   * 获取平台名称
   */
  static getPlatform(): string {
    return Capacitor.getPlatform()
  }
}


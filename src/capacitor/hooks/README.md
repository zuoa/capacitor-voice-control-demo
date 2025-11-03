# Capacitor Hooks

此目录用于存放自定义的 Capacitor hooks。

## 示例

### useCamera Hook

```typescript
import { Camera, CameraResultType } from '@capacitor/camera'

export function useCamera() {
  const takePicture = async () => {
    const image = await Camera.getPhoto({
      quality: 90,
      allowEditing: true,
      resultType: CameraResultType.Uri
    })
    return image
  }

  return { takePicture }
}
```

### useGeolocation Hook

```typescript
import { Geolocation } from '@capacitor/geolocation'

export function useGeolocation() {
  const getCurrentPosition = async () => {
    const position = await Geolocation.getCurrentPosition()
    return position
  }

  return { getCurrentPosition }
}
```

### useNetwork Hook

```typescript
import { Network } from '@capacitor/network'

export function useNetwork() {
  const getStatus = async () => {
    const status = await Network.getStatus()
    return status
  }

  const addListener = (callback: (status: any) => void) => {
    return Network.addListener('networkStatusChange', callback)
  }

  return { getStatus, addListener }
}
```

## 使用方式

在页面或组件中导入使用：

```typescript
import { useCamera } from '../capacitor/hooks/useCamera'

const { takePicture } = useCamera()
const image = await takePicture()
```


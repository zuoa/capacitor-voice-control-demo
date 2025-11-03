# Components

此目录用于存放可复用的组件。

## 目录结构

- **common/**: 通用组件（按钮、输入框、卡片等）
- **native/**: 原生功能包装组件
- **ui/**: UI 组件库

## 示例

### 原生功能包装组件

```typescript
// components/native/CameraButton.ts
import { useCamera } from '../../capacitor/hooks/useCamera'

export function createCameraButton() {
  const { takePicture } = useCamera()
  
  const button = document.createElement('button')
  button.textContent = '拍照'
  button.onclick = async () => {
    const image = await takePicture()
    console.log('Photo taken:', image)
  }
  
  return button
}
```

### UI 组件

```typescript
// components/ui/Button.ts
export interface ButtonProps {
  text: string
  onClick: () => void
  variant?: 'primary' | 'secondary'
}

export function createButton(props: ButtonProps) {
  const button = document.createElement('button')
  button.textContent = props.text
  button.className = `btn btn-${props.variant || 'primary'}`
  button.onclick = props.onClick
  return button
}
```

## 使用方式

```typescript
import { createCameraButton } from './components/native/CameraButton'
import { createButton } from './components/ui/Button'

// 使用原生组件
const cameraBtn = createCameraButton()
document.body.appendChild(cameraBtn)

// 使用 UI 组件
const btn = createButton({
  text: '点击我',
  onClick: () => console.log('Clicked!'),
  variant: 'primary'
})
document.body.appendChild(btn)
```


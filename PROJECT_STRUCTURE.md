# 项目结构说明

本项目已按照 Capacitor 最佳实践进行重组。

## 目录结构

```
speech-rec-demo/
├── android/                          # Capacitor Android 原生项目
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/                # 原生 Java 代码
│   │   │   ├── assets/              # 原生资源（模型文件等）
│   │   │   └── res/                 # Android 资源
│   │   └── libs/                    # 第三方库（sherpa-onnx, bdasr 等）
│   └── build.gradle
│
├── public/                           # 静态资源
│   ├── assets/
│   │   ├── icons/                   # 应用图标（待添加）
│   │   └── splash/                  # 启动屏幕（待添加）
│   ├── index.html                   # HTML 入口
│   └── vite.svg
│
├── src/
│   ├── capacitor/                   # Capacitor 相关代码
│   │   ├── plugins/                 # 自定义插件
│   │   │   ├── sherpa-onnx.ts      # Sherpa-ONNX 插件定义
│   │   │   └── index.ts            # 插件导出
│   │   ├── hooks/                   # Capacitor hooks（待添加）
│   │   └── utils/                   # 平台检测等工具
│   │       ├── platform.ts         # 平台检测工具
│   │       ├── permissions.ts      # 权限管理工具
│   │       └── index.ts
│   │
│   ├── components/                  # 组件（待添加）
│   │   ├── common/                 # 通用组件
│   │   ├── native/                 # 原生功能包装组件
│   │   └── ui/                     # UI 组件
│   │
│   ├── pages/                       # 页面组件
│   │   ├── SherpaOnnxPage.ts       # Sherpa-ONNX 测试页面
│   │   └── index.ts
│   │
│   ├── services/                    # 业务服务层
│   │   ├── CommandRecognitionSystem.ts  # 指令识别系统
│   │   └── index.ts
│   │
│   ├── store/                       # 状态管理（待添加）
│   │
│   ├── utils/                       # 工具函数
│   │   ├── examples.ts             # 使用示例
│   │   └── index.ts
│   │
│   ├── styles/                      # 样式文件
│   │   └── index.css               # 全局样式
│   │
│   ├── App.ts                       # 应用主组件
│   └── main.ts                      # 应用入口
│
├── capacitor.config.ts              # Capacitor 配置
├── package.json                     # 项目配置
├── vite.config.ts                   # Vite 构建配置
└── tsconfig.json                    # TypeScript 配置
```

## 主要改进

### 1. 清晰的模块分层

- **capacitor/**: 所有与 Capacitor 相关的代码
  - `plugins/`: 自定义插件定义
  - `utils/`: 平台检测、权限管理等工具

- **pages/**: 页面组件，每个页面负责 UI 渲染和交互

- **services/**: 业务逻辑层，封装复杂的业务逻辑

- **utils/**: 通用工具函数和使用示例

- **styles/**: 统一管理样式文件

### 2. 规范的导入路径

所有模块都通过 `index.ts` 统一导出，使用时导入路径更简洁：

```typescript
// 插件
import { SherpaOnnx, KeywordDetectedEvent } from '../capacitor/plugins'

// 工具
import { Platform, PermissionManager } from '../capacitor/utils'

// 服务
import { CommandRecognitionSystem } from '../services'

// 页面
import { createSherpaOnnxPage, setupSherpaOnnxPage } from '../pages'
```

### 3. 职责分明

- **App.ts**: 应用初始化和路由控制
- **pages/**: 负责 UI 展示和用户交互
- **services/**: 封装业务逻辑，提供可复用的服务
- **capacitor/plugins/**: 原生功能的 TypeScript 接口定义
- **capacitor/utils/**: 跨平台工具函数

## 使用说明

### 开发模式

```bash
npm run dev
```

### 构建

```bash
npm run build
```

### 同步到原生平台

```bash
npm run sync
```

### 打开原生项目

```bash
npm run android
```

## 扩展指南

### 添加新的自定义插件

1. 在 `src/capacitor/plugins/` 创建插件定义文件
2. 在 `src/capacitor/plugins/index.ts` 中导出
3. 在 `android/app/src/main/java/` 创建对应的原生实现

### 添加新页面

1. 在 `src/pages/` 创建新的页面组件
2. 在 `src/pages/index.ts` 中导出
3. 在 `src/App.ts` 中添加路由逻辑

### 添加新的业务服务

1. 在 `src/services/` 创建服务类
2. 在 `src/services/index.ts` 中导出
3. 在页面或其他服务中使用

### 添加 Capacitor Hooks

在 `src/capacitor/hooks/` 中创建自定义 hooks：

```typescript
// src/capacitor/hooks/useCamera.ts
import { Camera } from '@capacitor/camera'

export function useCamera() {
  // 封装相机相关功能
}
```

## 技术栈

- **构建工具**: Vite
- **语言**: TypeScript
- **跨平台框架**: Capacitor
- **原生端**: Android (Java)
- **语音识别**: Sherpa-ONNX (离线)

## 注意事项

1. 所有原生功能必须通过 Capacitor 插件访问
2. 使用 `Platform` 工具类检测当前运行平台
3. 样式文件统一放在 `src/styles/` 目录
4. 静态资源放在 `public/assets/` 目录
5. 开发时使用 TypeScript 类型定义确保类型安全


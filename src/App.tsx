import React from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { SherpaOnnxPage } from './pages'

export function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/sherpa" replace />} />
      <Route path="/sherpa" element={<SherpaOnnxPage />} />
      <Route path="*" element={<Navigate to="/sherpa" replace />} />
    </Routes>
  )
}



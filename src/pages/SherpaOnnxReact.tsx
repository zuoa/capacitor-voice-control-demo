import React, { useEffect, useRef } from 'react'
import { createSherpaOnnxPage, setupSherpaOnnxPage } from '.'

export function SherpaOnnxPage() {
  const containerRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    if (!containerRef.current) return
    containerRef.current.innerHTML = createSherpaOnnxPage()
    setupSherpaOnnxPage()
  }, [])

  return <div ref={containerRef} />
}



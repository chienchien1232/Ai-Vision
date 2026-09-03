package com.rayban.ai.core.camera

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner

interface CameraPreviewController {
    fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner)
    fun unbind()
}

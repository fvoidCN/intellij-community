package com.intellij.debugmap.listener

import com.intellij.debugmap.DebugMapService
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class DebugMapFileEditorListener(private val project: Project) : FileEditorManagerListener {

  override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
    DebugMapService.getInstance(project).onFileOpened(file)
  }

  override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
    DebugMapService.getInstance(project).onFileClosed(file)
  }
}

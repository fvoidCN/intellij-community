// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.dev.breakpoint

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.xdebugger.XDebuggerUtil

/**
 * Demo action: create a breakpoint (red dot) by specifying a coordinate string.
 *
 * Supported formats:
 *  1. Relative path + line:  "com/intellij/xdebugger/breakpoints/XBreakpoint.java:63"
 *  2. FQN + method:          "com.intellij.xdebugger.breakpoints.XBreakpoint#setLogExpressionObject"
 */
internal class SetBreakpointByCoordinateAction : AnAction("Set Breakpoint by Coordinate") {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val input = Messages.showInputDialog(
      project,
      "Enter coordinate:\n" +
      "  path/To/File.java:LINE\n" +
      "  com.example.ClassName#methodName",
      "Set Breakpoint by Coordinate",
      null,
      "",
      null
    )?.trim() ?: return

    ApplicationManager.getApplication().runReadAction {
      val result = resolveCoordinate(project, input)
      if (result == null) {
        ApplicationManager.getApplication().invokeLater {
          Messages.showErrorDialog(project, "Cannot resolve: $input", "Set Breakpoint")
        }
        return@runReadAction
      }
      val (file, line) = result
      ApplicationManager.getApplication().invokeLater {
        ApplicationManager.getApplication().runReadAction {
          XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, line)
        }
      }
    }
  }

  /**
   * Returns (VirtualFile, 0-based line) or null if the coordinate cannot be resolved.
   */
  private fun resolveCoordinate(project: Project, input: String): Pair<VirtualFile, Int>? {
    return if ('#' in input) {
      resolveMethodReference(project, input)
    } else {
      resolvePathWithLine(project, input)
    }
  }

  // "com/intellij/xdebugger/breakpoints/XBreakpoint.java:63"
  private fun resolvePathWithLine(project: Project, input: String): Pair<VirtualFile, Int>? {
    val colonIdx = input.lastIndexOf(':')
    if (colonIdx < 0) return null

    val pathPart = input.substring(0, colonIdx).trim()
    val lineOneBased = input.substring(colonIdx + 1).trim().toIntOrNull() ?: return null
    val lineZeroBased = lineOneBased - 1

    // Search all content source roots for a file matching the relative path
    val roots = ProjectRootManager.getInstance(project).contentSourceRoots
    val file = roots.firstNotNullOfOrNull { root ->
      root.findFileByRelativePath(pathPart)
    } ?: return null

    return file to lineZeroBased
  }

  // "com.intellij.xdebugger.breakpoints.XBreakpoint#setLogExpressionObject"
  private fun resolveMethodReference(project: Project, input: String): Pair<VirtualFile, Int>? {
    val hashIdx = input.indexOf('#')
    val className = input.substring(0, hashIdx).trim()
    val methodName = input.substring(hashIdx + 1).trim()

    val scope = GlobalSearchScope.allScope(project)
    val psiClass = JavaPsiFacade.getInstance(project).findClass(className, scope) ?: return null
    val method: PsiMethod = psiClass.findMethodsByName(methodName, true).firstOrNull() ?: return null

    val pos = XDebuggerUtil.getInstance().createPositionByElement(method) ?: return null
    return pos.file to pos.line
  }
}

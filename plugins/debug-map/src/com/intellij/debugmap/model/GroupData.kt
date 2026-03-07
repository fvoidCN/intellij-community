package com.intellij.debugmap.model

data class GroupData(
  val id: Int,
  val name: String,
  val breakpoints: List<BreakpointDef> = emptyList(),
  val bookmarks: List<BookmarkDef> = emptyList(),
)

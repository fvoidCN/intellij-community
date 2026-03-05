package com.intellij.debugmap.model

data class GroupData(
  val id: Int,
  val name: String,
  val createdAt: Long,
  val lastActivatedAt: Long = createdAt,
  val breakpoints: List<BreakpointDef> = emptyList(),
)

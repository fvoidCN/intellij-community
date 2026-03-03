package com.intellij.debugmap.model

data class GroupData(
  val id: Int,
  val annotation: String,
  val createdAt: Long,
  val lastActivatedAt: Long = createdAt,
  val breakpoints: List<BreakpointDef> = emptyList(),
)

package com.intellij.debugmap.model

import com.intellij.ide.bookmark.BookmarkType

data class BookmarkDef(
  override val groupId: Int,
  override val fileUrl: String,
  override val line: Int,
  override val name: String? = null,
  val type: BookmarkType = BookmarkType.DEFAULT,
) : LocationDef(groupId, fileUrl, line, name)

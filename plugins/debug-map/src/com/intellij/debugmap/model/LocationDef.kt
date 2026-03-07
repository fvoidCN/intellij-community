package com.intellij.debugmap.model

/**
 * Base class for a location-based definition owned by a group.
 * [line] is 0-based (matching [com.intellij.xdebugger.breakpoints.XLineBreakpoint.getLine]).
 *
 * Natural order: file name alphabetically, then by [line].
 * [compareTo] returns 0 iff (fileUrl, line) are equal — used by [java.util.TreeSet] for uniqueness.
 */
abstract class LocationDef(
  open val groupId: Int,
  open val fileUrl: String,
  open val line: Int,
  open val name: String? = null,
) : Comparable<LocationDef> {

  override fun compareTo(other: LocationDef): Int =
    compareValuesBy(
      this, other,
      { it.fileUrl.substringAfterLast('/') },
      { it.fileUrl },
      { it.line },
    )
}

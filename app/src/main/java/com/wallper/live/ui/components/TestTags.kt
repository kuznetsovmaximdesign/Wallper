package com.wallper.live.ui.components

/** Stable handles for UI tests (exposed as resource ids, see MainActivity). */
object TestTags {
    const val VIEWER = "viewer"
    const val VIDEO = "viewer-video"
    const val TITLE = "viewer-title"
    const val BACK = "viewer-back"
    const val DELETE = "viewer-delete"
    const val PREVIOUS = "viewer-previous"
    const val NEXT = "viewer-next"
    const val DOCK_SPEED = "dock-speed"
    const val DOCK_FRAGMENT = "dock-fragment"
    const val DOCK_FRAME = "dock-frame"
    const val SPEED_SLIDER = "speed-slider"
    const val FRAGMENT_SLIDER = "fragment-slider"
    const val INSTALL = "install"
    const val INSTALL_MORE = "install-more"
    const val INSTALL_LOCK = "install-lock"
    const val INSTALL_BOTH = "install-both"
    const val TAB_GALLERY = "tab-gallery"
    const val TAB_MINE = "tab-mine"
    const val IMPORT = "import"
    const val SETTINGS = "settings"
    const val GRID = "grid"

    fun card(id: String) = "card-$id"
    fun category(name: String) = "category-$name"
    fun setting(name: String) = "setting-$name"
}

package com.appblock.service

/**
 * The four strings the block screen's two fact rows are showing.
 *
 * Top-level rather than nested in [AppBlockerAccessibilityService] so that
 * `DisplayOverlays<WindowManager, View, FactRows>` can name it. Extraction only — the rendering that
 * produces one (`AppBlockerAccessibilityService.render`) is unchanged, and still lives in the service
 * because it needs `getString`.
 *
 * A `data class` on purpose: [com.appblock.engine.DisplayOverlays] compares the previous rows against
 * the new ones **per attachment** before writing, so the 5-second tick costs nothing until a rendered
 * minute actually rolls over. That comparison used to be against a single service-wide field, which
 * with two overlays would have left the second display's rows showing the layout's placeholders.
 */
internal data class FactRows(
    val whenLabel: String,
    val whenValue: String,
    val routeLabel: String,
    val routeValue: String,
)

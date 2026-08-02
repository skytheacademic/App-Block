package com.appblock

/**
 * Test handle for one stepper button.
 *
 * Tests used to reach these by global index, which broke twice as the layout moved (Save leaving the
 * scroll container, then the steppers moving into a sheet) — and would have broken a third time when
 * the redesign split the settings screen across four tabs. A tag keyed on the row's own label
 * survives any rearrangement, so it lives here rather than in whichever screen currently draws it.
 */
internal fun stepperTag(side: String, label: String): String = "stepper:$side:$label"

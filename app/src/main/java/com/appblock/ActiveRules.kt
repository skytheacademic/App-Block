package com.appblock

import com.appblock.engine.DefaultRules
import com.appblock.engine.Rule

/**
 * The rule set the running app enforces. Kept out of the pure engine (which stays Android-free and
 * JVM-testable) because it reads [BuildConfig]: the throwaway `debugFast` variant uses 1-minute caps
 * for quick on-device verification, every other build uses the real CONSTRAINTS.md v1.1 caps.
 */
object ActiveRules {
    val rules: List<Rule> =
        if (BuildConfig.FAST_CAPS) DefaultRules.fastRules else DefaultRules.rules
}

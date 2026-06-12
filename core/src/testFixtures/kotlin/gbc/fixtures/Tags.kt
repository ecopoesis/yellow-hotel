package gbc.fixtures

import io.kotest.core.Tag

/** Hardware test-ROM / large-suite tests, run by :core:accuracyTest. */
object Accuracy : Tag()

/** Real-time-multiple benchmarks, run by :core:perfTest. */
object Perf : Tag()

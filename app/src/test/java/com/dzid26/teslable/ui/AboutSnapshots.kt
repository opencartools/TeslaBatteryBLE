// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/** Paparazzi snapshots of the About links card: as on a CI pull request build (with its PR row) and on any other build. */
class AboutSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun linksPullRequestBuild() = paparazzi.snapCard("card-about-links-pr") { LinksCard(prNumber = "460") }

    @Test
    fun linksOtherBuild() = paparazzi.snapCard("card-about-links") { LinksCard(prNumber = "") }
}

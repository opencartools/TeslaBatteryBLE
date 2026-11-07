// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceUrlTest {
    @Test
    fun otherBuildsLinkTheRepository() {
        assertEquals("https://github.com/opencartools/TeslaBatteryBLE", sourceUrl(""))
    }

    @Test
    fun pullRequestBuildLinksItsPullRequest() {
        assertEquals("https://github.com/opencartools/TeslaBatteryBLE/pull/460", sourceUrl("460"))
    }
}

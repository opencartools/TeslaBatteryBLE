// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable

import android.app.Application
import com.dzid26.teslable.ble.DemoMode
import com.dzid26.teslable.ble.FakeCarDemo

/** Debug builds can flip the "Demo car" switch to simulate a vehicle. */
class DemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DemoMode.car = FakeCarDemo(this)
        DemoMode.restore(this)
        if (DemoMode.isEnabled()) {
            DemoHistory.seed(this)
        }
    }
}

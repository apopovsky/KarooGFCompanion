package com.karoogfcompanion.simulator

import com.itl.karoo.simulator.BaseKarooSimulatorActivity
import com.itl.karoo.simulator.KarooSimulatorPlugin

class GfSimulatorActivity : BaseKarooSimulatorActivity() {
    override val plugin: KarooSimulatorPlugin = GfSimulatorPlugin()
}

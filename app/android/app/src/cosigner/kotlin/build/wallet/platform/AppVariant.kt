package build.wallet.platform

import build.wallet.platform.config.AppVariant

// Customer rather than Development: this is a real build, so no debug menu, no
// simulated hardware, and production defaults.
val appVariant = AppVariant.Customer

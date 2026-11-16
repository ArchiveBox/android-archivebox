package io.archivebox.app.data

import io.archivebox.app.ui.adminRoutes
import io.archivebox.app.ui.collectionRoutes
import org.junit.Assert.assertEquals
import org.junit.Test

class SidebarRoutesTest {
    @Test fun sidebarUsesCoreNavigationRoutes() {
        val routes = (collectionRoutes + adminRoutes).associate { it.name to it.path }
        assertEquals("admin/importers/", routes["Importers"])
        assertEquals("admin/domains/", routes["Domains"])
        assertEquals("admin/webhooks/", routes["Rules Engine"])
        assertEquals("admin/machine/machine/", routes["System Settings"])
        // The Rules Engine is not the raw outbound-webhook model admin.
        assertEquals("admin/api/outboundwebhook/", routes["Webhooks"])
    }
}

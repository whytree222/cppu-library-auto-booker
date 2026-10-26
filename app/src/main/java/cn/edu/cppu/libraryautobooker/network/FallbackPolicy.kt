package cn.edu.cppu.libraryautobooker.network

/**
 * Direct-first route with a single, one-way switch to WebVPN. The switch is only
 * allowed before any submission attempt and only for a real network failure.
 * Once a batch may have reached the school, the route is frozen so an unknown
 * result can never be resent through another path.
 */
class FallbackPolicy(private val enabled: Boolean) {
    var route = LibraryRoute.DIRECT
        private set
    private var submitted = false

    @Synchronized fun markSubmission(source: LibraryRoute = route): Boolean {
        if (source != route) return false
        submitted = true
        return true
    }

    @Synchronized fun switchForNetworkFailure(): Boolean {
        if (!enabled || submitted || route != LibraryRoute.DIRECT) return false
        route = LibraryRoute.WEBVPN
        return true
    }

    @Synchronized fun confirmedNextBatch() { submitted = false }

    @Synchronized fun hasSubmitted() = submitted
}

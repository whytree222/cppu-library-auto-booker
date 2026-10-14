package cn.edu.cppu.libraryautobooker.network

/** One-way route switch per task; pessimistically lock before any reservation send. */
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


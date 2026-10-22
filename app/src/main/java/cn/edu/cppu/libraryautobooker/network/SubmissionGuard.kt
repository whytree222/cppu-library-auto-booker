package cn.edu.cppu.libraryautobooker.network

/** Keep unknown-result protection even though there is only one network route. */
class SubmissionGuard {
    private var submitted = false
    @Synchronized fun markSubmission(source: LibraryRoute = LibraryRoute.DIRECT): Boolean {
        if (source != LibraryRoute.DIRECT) return false
        submitted = true
        return true
    }
    @Synchronized fun confirmedNextBatch() { submitted = false }
    @Synchronized fun hasSubmitted() = submitted
}

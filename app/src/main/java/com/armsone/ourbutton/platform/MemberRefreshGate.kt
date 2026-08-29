package com.armsone.ourbutton.platform

import java.util.UUID

/** Rejects late /members responses after a newer refresh or space switch. */
class MemberRefreshGate {
    class Request internal constructor(val spaceID: UUID, val generation: Long)

    private var generation = 0L

    @Synchronized
    fun begin(spaceID: UUID): Request = Request(spaceID, ++generation)

    @Synchronized
    fun accepts(request: Request, activeSpaceID: UUID?): Boolean =
        request.generation == generation && request.spaceID == activeSpaceID

    @Synchronized
    fun invalidate() {
        generation += 1
    }
}

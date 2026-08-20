package com.armsone.button.model

import java.time.Instant
import java.util.UUID

data class NearbyFamilyMember(
    val id: UUID,
    var name: String,
    var role: FamilyRole?,
    var lastSeen: Instant,
    var isCurrentDevice: Boolean,
)
